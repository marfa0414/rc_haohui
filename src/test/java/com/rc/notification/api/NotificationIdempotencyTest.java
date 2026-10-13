package com.rc.notification.api;

import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP02: TC-IDEM-01..04, TC-API-01 durable acceptance; CP03 mapping is intentionally not mocked in. */
@Timeout(90)
class NotificationIdempotencyTest {
    @Test
    void fiftySimultaneousFirstSubmissionsProduceOneDurableTask() throws Exception {
        try (var api = concurrentFixture()) {
            List<String> payloads = java.util.Collections.nCopies(50,
                    payload("tenant-a", "user.registered", "same-event", "{\"order\":123}"));
            var responses = submitAtInsertBarrier(api, payloads);
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 202).count());
            assertEquals(49, responses.stream().filter(response -> response.statusCode() == 200).count());
            Set<String> ids = new HashSet<>();
            for (var response : responses) {
                var json = api.json(response);
                ids.add(json.path("notificationId").asText());
                assertEquals(response.statusCode() == 200, json.path("deduplicated").asBoolean());
                assertEquals("pending", json.path("status").asText());
                assertEquals(1, json.path("runNumber").asInt());
            }
            assertEquals(1, ids.size());
            assertEquals(1, api.taskCount());
            assertEquals(200, api.get("/v1/notifications/" + ids.iterator().next(), CALLER_A).statusCode());
        }
    }

    @Test
    void competingContentsHaveOneWinnerAndTheOtherAlwaysConflicts() throws Exception {
        try (var api = concurrentFixture()) {
            String one = payload("tenant-a", "user.registered", "race-event", "{\"amount\":9007199254740992}");
            String two = payload("tenant-a", "user.registered", "race-event", "{\"amount\":9007199254740993}");
            List<String> payloads = new ArrayList<>();
            for (int i = 0; i < 50; i++) payloads.add(i % 2 == 0 ? one : two);
            var responses = submitAtInsertBarrier(api, payloads);
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 202).count());
            assertEquals(24, responses.stream().filter(response -> response.statusCode() == 200).count());
            assertEquals(25, responses.stream().filter(response -> response.statusCode() == 409).count());
            int winnerIndex = java.util.stream.IntStream.range(0, responses.size())
                    .filter(index -> responses.get(index).statusCode() == 202).findFirst().orElseThrow();
            String winnerId = api.json(responses.get(winnerIndex)).path("notificationId").asText();
            String winnerPayload = payloads.get(winnerIndex);
            var winnerRetry = api.post(winnerPayload, CALLER_A);
            var loserRetry = api.post(winnerPayload.equals(one) ? two : one, CALLER_A);
            assertEquals(200, winnerRetry.statusCode());
            assertEquals(winnerId, api.json(winnerRetry).path("notificationId").asText());
            assertEquals(409, loserRetry.statusCode());
            assertEquals("EVENT_CONFLICT", api.json(loserRetry).path("code").asText());
            assertEquals(1, api.taskCount());
        }
    }

    static Stream<Arguments> canonicalPairs() {
        return Stream.of(
                Arguments.of("key-order", "{\"z\":2,\"a\":1}", "{\"a\":1,\"z\":2}", true),
                Arguments.of("nested-order", "{\"n\":{\"z\":2,\"a\":1}}", "{\"n\":{\"a\":1,\"z\":2}}", true),
                Arguments.of("numeric-scale", "{\"n\":1}", "{\"n\":1.0}", true),
                Arguments.of("numeric-exponent", "{\"n\":1}", "{\"n\":1e0}", true),
                Arguments.of("negative-zero", "{\"n\":-0}", "{\"n\":0.000}", true),
                Arguments.of("exact-big-integer", "{\"n\":123456789012345678901234567890}", "{\"n\":12345678901234567890123456789e1}", true),
                Arguments.of("different-big-integers", "{\"n\":9007199254740992}", "{\"n\":9007199254740993}", false),
                Arguments.of("different-fine-decimals", "{\"n\":0.123456789012345678901}", "{\"n\":0.123456789012345678902}", false),
                Arguments.of("decimal-trailing-zero", "{\"n\":0.1234567890123456789010}", "{\"n\":0.123456789012345678901}", true),
                Arguments.of("array-order", "{\"n\":[1,2]}", "{\"n\":[2,1]}", false),
                Arguments.of("null-and-missing", "{\"n\":null}", "{}", false),
                Arguments.of("string-case", "{\"n\":\"Case\"}", "{\"n\":\"case\"}", false),
                Arguments.of("string-and-number", "{\"n\":\"1\"}", "{\"n\":1}", false),
                Arguments.of("unicode-codepoint-keys", "{\"\uD800\uDC00\":1,\"\uE000\":2}", "{\"\uE000\":2,\"\uD800\uDC00\":1}", true),
                Arguments.of("emoji-and-question-mark", "{\"n\":\"\\uD83D\\uDE00\"}", "{\"n\":\"?\"}", false),
                Arguments.of("emoji-literal-and-escaped-pair", "{\"n\":\"😀\"}", "{\"n\":\"\\uD83D\\uDE00\"}", true),
                Arguments.of("unicode-no-normalization", "{\"n\":\"é\"}", "{\"n\":\"é\"}", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("canonicalPairs")
    void hashesOriginalDataWithoutPrecisionLoss(String caseName, String firstData, String secondData, boolean same)
            throws Exception {
        try (var api = new NotificationApiFixture()) {
            String event = "canonical-" + UUID.randomUUID();
            var first = api.post(payload("tenant-a", "user.registered", event, firstData), CALLER_A);
            var second = api.post(payload("tenant-a", "user.registered", event, secondData), CALLER_A);
            assertEquals(202, first.statusCode(), first.body());
            assertEquals(same ? 200 : 409, second.statusCode(), caseName + ": " + second.body());
            if (same) assertEquals(api.json(first).path("notificationId"), api.json(second).path("notificationId"));
            assertEquals(1, api.taskCount());
        }
    }

    @Test
    void tenantAndOperationAreBothPartsOfTheEventKey() throws Exception {
        try (var api = new NotificationApiFixture()) {
            Set<String> ids = new HashSet<>();
            for (String tenant : List.of("tenant-a", "tenant-b")) {
                for (String operation : List.of("user.registered", "order.paid")) {
                    var result = api.post(payload(tenant, operation, "shared-event", "{}"), ADMIN);
                    assertEquals(202, result.statusCode(), result.body());
                    ids.add(api.json(result).path("notificationId").asText());
                }
            }
            assertEquals(4, ids.size());
            assertEquals(4, api.taskCount());
        }
    }

    @Test
    void existingEventIsAuthorizedThenDeduplicatedBeforeReadingTheCurrentRoute() throws Exception {
        try (var api = new NotificationApiFixture()) {
            String payload = payload("tenant-a", "user.registered", "prior-event", "{\"secret\":\"stored-input-sentinel\"}");
            var created = api.post(payload, CALLER_A);
            assertEquals(202, created.statusCode(), created.body());
            api.sql("DELETE FROM notification_route WHERE tenant_id='tenant-a' AND operation='user.registered'");
            var repeated = api.post(payload, CALLER_A);
            assertEquals(200, repeated.statusCode(), repeated.body());
            assertEquals(api.json(created).path("notificationId"), api.json(repeated).path("notificationId"));
            assertEquals(403, api.post(payload, CALLER_B).statusCode());
            assertEquals(403, api.post(payload, LIMITED).statusCode());
            assertEquals(422, api.post(payload("tenant-a", "user.registered", "new-event", "{}"), CALLER_A).statusCode());
            assertEquals(1, api.taskCount());
        }
    }

    private static NotificationApiFixture concurrentFixture() throws Exception {
        return new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                "spring.datasource.hikari.maximum-pool-size", "55",
                "spring.datasource.hikari.minimum-idle", "0",
                "spring.jdbc.template.query-timeout", "10s",
                "spring.datasource.hikari.connection-timeout", "10000"));
    }

    private static List<HttpResponse<String>> submitAtInsertBarrier(NotificationApiFixture api, List<String> payloads)
            throws Exception {
        long lockKey = Integer.toUnsignedLong(api.database.schema().hashCode());
        api.sql("CREATE FUNCTION test_insert_barrier() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "PERFORM pg_advisory_xact_lock(" + lockKey + "); RETURN NEW; END $$");
        api.sql("CREATE TRIGGER test_insert_barrier BEFORE INSERT ON notification_task "
                + "FOR EACH ROW EXECUTE FUNCTION test_insert_barrier()");
        var callers = Executors.newFixedThreadPool(payloads.size());
        CountDownLatch ready = new CountDownLatch(payloads.size());
        CountDownLatch start = new CountDownLatch(1);
        try (Connection lock = api.database.connection()) {
            try (var statement = lock.createStatement()) { statement.execute("SELECT pg_advisory_lock(" + lockKey + ")"); }
            List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String payload : payloads) {
                futures.add(callers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return api.post(payload, CALLER_A);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
            int waiting = 0;
            while (System.nanoTime() < deadline) {
                try (var statement = lock.createStatement(); var rows = statement.executeQuery(
                        "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND classid=0 AND objid=" + lockKey)) {
                    rows.next();
                    waiting = rows.getInt(1);
                }
                if (waiting == payloads.size()) break;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            try (var statement = lock.createStatement()) { statement.execute("SELECT pg_advisory_unlock(" + lockKey + ")"); }
            assertEquals(payloads.size(), waiting, "All first submissions must reach the database INSERT before any can commit");
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            callers.shutdownNow();
        }
    }
}
