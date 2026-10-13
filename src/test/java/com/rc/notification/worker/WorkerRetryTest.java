package com.rc.notification.worker;

import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CP05 TC-RETRY-01..04/06/07, TC-WORK-07/08, TC-OBS-01 real HTTP and database transitions. */
@Timeout(60)
class WorkerRetryTest {
    @Test
    void twoServerFailuresReallyBackOffThenSucceedWithOneRequestPerAttempt() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, calls.incrementAndGet() < 3 ? 500 : 200, "ignored"));
            var id = f.submit("retry-sequence", "{\"payload\":{\"exact\":9007199254740993}}");
            var original = f.configuration.bodyBytes(id.toString());
            var deadline = f.task(id).path("deadline_at");
            for (int attempt = 1; attempt <= 3; attempt++) {
                var state = executeNextAttempt(f, id);
                assertEquals(attempt == 3 ? "succeeded" : "pending", state.path("status").asText());
                assertEquals(attempt, state.path("run_attempt_count").asInt());
                assertEquals(attempt, f.mock.journal().size());
                assertEquals(deadline, state.path("deadline_at"));
                assertArrayEquals(original, f.mock.journal().get(attempt - 1).body());
                if (attempt < 3) {
                    assertEquals("HTTP_REJECTED", state.path("last_outcome").asText());
                    Instant next = Instant.parse(state.path("next_attempt_at").asText());
                    assertFalse(next.isBefore(f.mock.journal().get(attempt - 1).receivedAt()));
                    if (next.isAfter(Instant.now())) assertEquals(0, f.worker.tick());
                }
            }
            var history = f.attempts(id);
            assertEquals(3, history.size());
            assertEquals(500, history.get(0).path("http_status").asInt());
            assertEquals(500, history.get(1).path("http_status").asInt());
            assertEquals(200, history.get(2).path("http_status").asInt());
            var metrics = f.api.application.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            assertEquals(2.0, metrics.get("notification.worker.decision").tag("decision", "RETRY").counter().count());
            assertEquals(1.0, metrics.get("notification.worker.decision").tag("decision", "SUCCESS").counter().count());
            f.evidence("cp05-500-500-200", id, null);
        }
    }

    @ParameterizedTest @ValueSource(ints = {3, 12})
    void exhaustedAttemptBudgetHasNoAdditionalReservationOrPhysicalSend(int maximum) throws Exception {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.put("app.delivery.max-attempts", Integer.toString(maximum));
        // CP07: isolate the budget invariant from independently tested default ten-sample opening.
        if (maximum == 12) properties.put("app.protection.minimum-calls", "20");
        try (var f = new WorkerFixture(properties)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 500, "permanent-outage"));
            var id = f.submit("budget-" + maximum, "{}");
            for (int n = 1; n <= maximum; n++) {
                var state = executeNextAttempt(f, id);
                assertEquals(n == maximum ? "failed" : "pending", state.path("status").asText());
                assertEquals(n, state.path("run_attempt_count").asInt());
            }
            for (int scan = 0; scan < 3; scan++) assertEquals(0, f.worker.tick());
            assertEquals(maximum, f.mock.journal().size());
            assertEquals(maximum, f.attempts(id).size());
            assertEquals("HTTP_REJECTED", f.task(id).path("last_outcome").asText());
            assertEquals("ATTEMPTS_EXHAUSTED", f.task(id).path("defer_reason").asText());
            var metrics = f.api.application.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            assertEquals(maximum - 1.0, metrics.get("notification.worker.decision").tag("decision", "RETRY").counter().count());
            assertEquals(1.0, metrics.get("notification.worker.decision").tag("decision", "FAIL").counter().count());
            f.evidence("cp05-attempt-budget-" + maximum, id, null);
        }
    }

    @ParameterizedTest
    @CsvSource({"200,succeeded", "204,succeeded", "301,failed", "302,failed", "307,failed", "308,failed",
            "400,failed", "401,failed", "403,failed", "404,failed", "409,failed", "408,pending", "429,pending",
            "500,pending", "502,pending", "503,pending", "504,pending"})
    void physicalHttpResultsFollowTheClassificationWithoutHiddenRetries(int status, String expected) throws Exception {
        try (var f = new WorkerFixture(Map.of("app.retry.base-delay", "1s", "app.retry.max-delay", "1s"))) {
            f.mock.route("/notify", e -> {
                e.getResponseHeaders().set("Location", f.mock.uri("/redirect-target").toString());
                HttpMock.respond(e, status, "{\"businessFailure\":true}");
            });
            f.mock.route("/redirect-target", e -> HttpMock.respond(e, 200, "must-not-follow"));
            var id = f.submit("classification-" + status, "{}");
            var task = executeNextAttempt(f, id);
            assertEquals(expected, task.path("status").asText());
            assertEquals(1, f.mock.count("/notify"));
            assertEquals(0, f.mock.count("/redirect-target"));
            assertEquals(1, f.attempts(id).size());
            assertEquals(status, task.path("last_http_status").asInt());
            assertEquals(status < 300 ? "HTTP_ACKNOWLEDGED" : "HTTP_REJECTED", task.path("last_outcome").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"seconds", "date"})
    void retryAfterIsARealArrivalLowerBoundEvenAboveTheLocalBackoffCap(String kind) throws Exception {
        var calls = new AtomicInteger();
        var minimum = new java.util.concurrent.atomic.AtomicReference<Instant>();
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> {
                if (calls.incrementAndGet() == 1) {
                    if (kind.equals("seconds")) {
                        minimum.set(Instant.now().plusSeconds(1));
                        e.getResponseHeaders().set("Retry-After", "1");
                    } else {
                        Instant date = Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                        minimum.set(date);
                        e.getResponseHeaders().set("Retry-After", HttpMock.httpDate(date));
                    }
                    HttpMock.respond(e, 429, "quota");
                } else HttpMock.respond(e, 200, "ok");
            });
            var id = f.submit("retry-after-" + kind, "{}");
            var pending = executeNextAttempt(f, id);
            assertEquals("pending", pending.path("status").asText());
            assertFalse(Instant.parse(pending.path("next_attempt_at").asText()).isBefore(minimum.get()));
            assertEquals(0, f.worker.tick());
            assertEquals(1, f.mock.journal().size());
            assertEquals("succeeded", executeNextAttempt(f, id).path("status").asText());
            assertFalse(f.mock.journal().get(1).receivedAt().isBefore(minimum.get()));
            f.evidence("cp05-retry-after-" + kind, id, null);
        }
    }

    @Test
    void anOverflowingButValidRetryAfterExceedsDeadlineInsteadOfRetryingEarly() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> {
                e.getResponseHeaders().set("Retry-After", "999999999999999999999999999999999999999");
                HttpMock.respond(e, 503, "long-outage");
            });
            var id = f.submit("retry-after-overflow", "{}");
            var state = executeNextAttempt(f, id);
            assertEquals("failed", state.path("status").asText());
            assertEquals("DEADLINE_EXCEEDED", state.path("defer_reason").asText());
            assertEquals("HTTP_REJECTED", state.path("last_outcome").asText());
            assertEquals(0, f.worker.tick());
            assertEquals(1, f.mock.journal().size());
        }
    }

    @Test
    void independentExpiryFindsAFarFutureScheduleAndPreservesTheLastObservedHttpResult() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 500, "previous-observed-response"));
            var id = f.submit("future-schedule-expiry", "{}");
            var prior = executeNextAttempt(f, id);
            assertEquals("pending", prior.path("status").asText());
            f.api.sql("UPDATE notification_task SET deadline_at=clock_timestamp()+interval '150 milliseconds',"
                    + "next_attempt_at=clock_timestamp()+interval '5 days' WHERE id='" + id + "'");
            awaitDeadline(f, id);
            assertEquals(1, f.repository.expirePending(100));
            assertEquals(0, f.repository.expirePending(100));
            var failed = f.task(id);
            assertEquals("failed", failed.path("status").asText());
            assertEquals("DEADLINE_EXCEEDED", failed.path("defer_reason").asText());
            for (String field : new String[]{"last_outcome", "last_http_status", "last_error_code", "total_attempt_count"})
                assertEquals(prior.path(field), failed.path(field), field);
            assertEquals(1, f.attempts(id).size());
            assertEquals(0, f.worker.tick());
            assertEquals(1, f.mock.journal().size());
            f.evidence("cp05-independent-expiry", id, null);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aLostResponseAfterASupplierEffectIsUnknownAndRetriesWithTheSameEvent(boolean timeout) throws Exception {
        var effects = new AtomicInteger();
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> {
                if (effects.incrementAndGet() == 1) {
                    if (timeout) LockSupport.parkNanos(Duration.ofMillis(1300).toNanos());
                    e.close();
                } else HttpMock.respond(e, 200, "applied-again");
            });
            var id = f.submit("unknown-event-" + timeout, "{}");
            var pending = executeNextAttempt(f, id);
            assertEquals("pending", pending.path("status").asText());
            assertEquals("UNKNOWN", pending.path("last_outcome").asText());
            assertEquals(1, effects.get());
            f.evidence("cp05-unknown-" + timeout + "-first", id, effects.get());
            assertEquals("succeeded", executeNextAttempt(f, id).path("status").asText());
            assertEquals(2, effects.get());
            assertEquals(2, f.mock.journal().size());
            assertEquals(f.mock.journal().get(0).firstHeader("X-Event-Id"), f.mock.journal().get(1).firstHeader("X-Event-Id"));
            assertArrayEquals(f.mock.journal().get(0).body(), f.mock.journal().get(1).body());
            assertEquals("UNKNOWN", f.attempts(id).get(0).path("outcome").asText());
            f.evidence("cp05-unknown-" + timeout + "-recovered", id, effects.get());
        }
    }

    @Test
    void retryAfterExactlyAtTheRoundDeadlineTerminatesWithoutQueueingAnotherAttempt() throws Exception {
        Instant deadline = Instant.now().plusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> {
                e.getResponseHeaders().set("Retry-After", HttpMock.httpDate(deadline));
                HttpMock.respond(e, 503, "next-at-deadline");
            });
            var id = f.submit("retry-equal-deadline", "{}");
            f.api.sql("UPDATE notification_task SET deadline_at='" + deadline + "' WHERE id='" + id + "'");
            var failed = executeNextAttempt(f, id);
            assertEquals("failed", failed.path("status").asText());
            assertEquals("DEADLINE_EXCEEDED", failed.path("defer_reason").asText());
            assertEquals(Instant.parse(failed.path("deadline_at").asText()), deadline);
            assertEquals(1, f.mock.journal().size()); assertEquals(0, f.worker.tick());
        }
    }

    @Test
    void recoveringAClaimWithoutAnAttemptKeepsThePreviousHttpOutcomeAndHistory() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 500, "last-known-result"));
            var id = f.submit("claim-with-no-new-attempt", "{}");
            var previous = executeNextAttempt(f, id);
            var history = f.attempts(id);
            awaitDue(f, id, Duration.ofSeconds(2));
            assertTrue(f.repository.claim("cp04-provider", "lost-before-reserve", Duration.ofMillis(200)).isPresent());
            f.awaitExpiredLease(id, Duration.ofSeconds(2));
            assertEquals(1, f.repository.recoverExpired(100));
            var recovered = f.task(id);
            assertEquals("pending", recovered.path("status").asText());
            for (String field : new String[]{"last_outcome", "last_http_status", "last_error_code", "total_attempt_count", "run_attempt_count", "deadline_at"})
                assertEquals(previous.path(field), recovered.path(field), field);
            assertEquals(history, f.attempts(id));
            assertEquals(1, f.mock.journal().size());
            f.evidence("cp05-claim-no-attempt-preserved", id, null);
        }
    }

    private static void awaitDeadline(WorkerFixture f, java.util.UUID id) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do {
            try (var c = f.api.database.connection(); var s = c.createStatement();
                    var r = s.executeQuery("SELECT deadline_at<=clock_timestamp() FROM " + f.api.database.schema()
                            + ".notification_task WHERE id='" + id + "'")) {
                assertTrue(r.next()); if (r.getBoolean(1)) return;
            }
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        } while (System.nanoTime() < limit);
        fail("Real PostgreSQL deadline did not expire");
    }
}
