package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import com.rc.notification.support.WorkerFaults;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CP05 TC-REPLAY-01..05, TC-RETRY-08 and TC-WORK-06: real failed rounds, replay transactions and wire loss. */
@Timeout(90)
class ReplayTest {
    static ObjectNode body(String key, int run, String reason) {
        return JSON.createObjectNode().put("replayRequestId", key).put("expectedRunNumber", run).put("reason", reason);
    }
    static HttpResponse<String> replay(WorkerFixture f, UUID id, JsonNode body, String caller) throws Exception {
        return f.api.send("POST", "/admin/notifications/" + id + "/replay", body, caller);
    }
    static int count(WorkerFixture f, String table, UUID id) throws Exception {
        try (var connection = f.api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + f.api.database.schema() + "." + table
                        + " WHERE " + (table.equals("audit_event") ? "object_id='" : "task_id='") + id + "'")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }

    @Test
    void replayPreservesTheSnapshotHistoryAndLastOutcomeThenUsesTheNewDeploymentsRoundBudget() throws Exception {
        var status = new AtomicInteger(400);
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, status.get(), "result"));
            var id = f.submit("replay-budget", "{\"payload\":{\"value\":1}}");
            var original = f.task(id);
            assertEquals(12, original.path("max_attempts").asInt());
            Duration defaultRound = Duration.between(Instant.parse(original.path("created_at").asText()), Instant.parse(original.path("deadline_at").asText()));
            assertTrue(defaultRound.minus(Duration.ofHours(24)).abs().compareTo(Duration.ofSeconds(1)) < 0);
            var failed = executeNextAttempt(f, id);
            assertEquals("failed", failed.path("status").asText());
            var oldBytes = f.configuration.bodyBytes(id.toString());
            var oldHistory = f.attempts(id);
            f.restart(Map.of("app.delivery.max-attempts", "3", "app.delivery.run-duration", "2h"));
            assertEquals(12, f.task(id).path("max_attempts").asInt());
            assertEquals(original.path("deadline_at"), f.task(id).path("deadline_at"));
            var fresh = f.submit("new-deploy-budget", "{}");
            assertEquals(3, f.task(fresh).path("max_attempts").asInt());
            executeNextAttempt(f, fresh);
            // Remove the original route entirely: replay must use the original immutable task snapshot.
            f.api.sql("DELETE FROM notification_route WHERE tenant_id='tenant-a' AND operation='worker.notify'");
            var request = JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/recovery/replay.json")));
            f.api.sql("UPDATE notification_task SET auth_recovery_used=true,auth_recovery_required=true,"
                    + "rejected_token_digest=decode(repeat('aa',32),'hex') WHERE id='" + id + "'");
            var response = replay(f, id, request, OPERATOR_A);
            assertEquals(200, response.statusCode(), response.body());
            var pending = f.task(id);
            assertEquals(2, pending.path("run_number").asInt());
            assertEquals(0, pending.path("run_attempt_count").asInt());
            assertEquals(1, pending.path("total_attempt_count").asInt());
            assertEquals(3, pending.path("max_attempts").asInt());
            assertFalse(pending.path("auth_recovery_used").asBoolean());
            assertFalse(pending.path("auth_recovery_required").asBoolean());
            assertTrue(pending.path("rejected_token_digest").isNull());
            assertEquals("pending", pending.path("status").asText());
            assertEquals(failed.path("last_outcome"), pending.path("last_outcome"));
            assertEquals(failed.path("last_http_status"), pending.path("last_http_status"));
            assertEquals(oldHistory, f.attempts(id));
            assertArrayEquals(oldBytes, f.configuration.bodyBytes(id.toString()));
            assertTrue(Duration.between(Instant.now(), Instant.parse(pending.path("deadline_at").asText())).minus(Duration.ofHours(2)).abs()
                    .compareTo(Duration.ofSeconds(5)) < 0);
            assertEquals(1, count(f, "replay_request", id));
            assertEquals(1, count(f, "audit_event", id));
            f.evidence("cp05-replay-pending-preserved", id, null);
            status.set(200);
            executeNextAttempt(f, id);
            assertEquals("succeeded", f.task(id).path("status").asText());
            assertEquals(f.api.json(response), f.api.json(replay(f, id, request, OPERATOR_A)), "Same key must return its fixed original response after success");
            assertEquals(2, f.task(id).path("run_number").asInt());
            assertEquals(2, f.task(id).path("total_attempt_count").asInt());
            f.evidence("cp05-replay-completed", id, null);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fiftyConcurrentReplayCallsCreateExactlyOneRoundWithOneAudit(boolean sameKey) throws Exception {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.putAll(Map.of("spring.datasource.hikari.maximum-pool-size", "55", "spring.datasource.hikari.minimum-idle", "0",
                "spring.datasource.hikari.connection-timeout", "10000", "spring.jdbc.template.query-timeout", "10s"));
        try (var f = new WorkerFixture(properties)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 400, "permanent"));
            var id = f.submit("replay-race-" + sameKey, "{}");
            assertEquals("failed", executeNextAttempt(f, id).path("status").asText());
            var executor = Executors.newFixedThreadPool(50);
            var ready = new CountDownLatch(50);
            var start = new CountDownLatch(1);
            try (var blocker = f.api.database.connection(); var lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                try (var row = lock.executeQuery("SELECT id FROM " + f.api.database.schema() + ".notification_task WHERE id='" + id + "' FOR UPDATE")) {
                    assertTrue(row.next());
                }
                List<java.util.concurrent.Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int index = 0; index < 50; index++) {
                    String key = sameKey ? "shared-key" : "different-key-" + index;
                    futures.add(executor.submit(() -> {
                        ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                        return replay(f, id, body(key, 1, "operator-recovery"), OPERATOR_A);
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                long bound = System.nanoTime() + Duration.ofSeconds(7).toNanos();
                int waiting = 0;
                while (System.nanoTime() < bound) {
                    // PostgreSQL caches statistics inside a transaction. Observe via a fresh autocommit
                    // connection, not the intentionally open transaction holding the task lock.
                    try (var observer = f.api.database.connection(); var observation = observer.createStatement();
                            var rows = observation.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                            + "AND query LIKE '%notification_task%'")) { assertTrue(rows.next()); waiting = rows.getInt(1); }
                    if (waiting == 50) break;
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
                }
                assertEquals(50, waiting, "All HTTP calls must compete at the real task row lock before release");
                blocker.commit();
                List<HttpResponse<String>> responses = new ArrayList<>();
                for (var future : futures) responses.add(future.get(10, TimeUnit.SECONDS));
                assertEquals(sameKey ? 50 : 1, responses.stream().filter(r -> r.statusCode() == 200).count());
                assertEquals(sameKey ? 0 : 49, responses.stream().filter(r -> r.statusCode() == 409).count());
                if (sameKey) {
                    var fixed = f.api.json(responses.get(0));
                    for (var response : responses) assertEquals(fixed, f.api.json(response));
                }
                assertEquals(2, f.task(id).path("run_number").asInt());
                assertEquals(0, f.task(id).path("run_attempt_count").asInt());
                assertEquals(1, count(f, "replay_request", id));
                assertEquals(1, count(f, "audit_event", id));
                assertEquals(1, f.mock.journal().size());
                f.evidence("cp05-replay-race-" + sameKey, id, null);
            } finally { start.countDown(); executor.shutdownNow(); }
        }
    }

    @Test
    void lostReplayResponseIsResolvedByItsOwnKeyAndChangedParametersConflict() throws Exception {
        try (var f = new WorkerFixture(fastRetries()); var proxy = new HttpMock()) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 400, "failed"));
            var id = f.submit("replay-lost-response", "{}");
            executeNextAttempt(f, id);
            var request = body("lost-replay-key", 1, "operator-action");
            var committed = new AtomicReference<HttpResponse<String>>();
            var reached = new CountDownLatch(1);
            proxy.route("/replay", e -> {
                try { committed.set(replay(f, id, request, OPERATOR_A)); reached.countDown(); }
                catch (Exception exception) { throw new IOException(exception); }
                finally { e.close(); }
            });
            var external = HttpRequest.newBuilder(proxy.uri("/replay")).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            assertThrows(IOException.class, () -> HTTP.send(external, HttpResponse.BodyHandlers.ofString()));
            assertTrue(reached.await(2, TimeUnit.SECONDS));
            assertEquals(200, committed.get().statusCode());
            var retry = replay(f, id, request, OPERATOR_A);
            assertEquals(200, retry.statusCode(), retry.body());
            assertEquals(f.api.json(committed.get()), f.api.json(retry));
            for (var changed : List.of(body("lost-replay-key", 1, "changed-reason"), body("lost-replay-key", 2, "operator-action"))) {
                var conflict = replay(f, id, changed, OPERATOR_A);
                assertEquals(409, conflict.statusCode());
                assertEquals("REPLAY_CONFLICT", f.api.json(conflict).path("code").asText());
            }
            assertEquals(1, count(f, "replay_request", id));
            assertEquals(1, count(f, "audit_event", id));
            assertEquals(2, f.task(id).path("run_number").asInt());
            f.evidence("cp05-replay-lost-response", id, null);
        }
    }

    @Test
    void replayAuditFailureRollsBackTheNewRoundAndItsIdempotencyRecord() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 400, "failed"));
            var id = f.submit("replay-audit-rollback", "{}"); executeNextAttempt(f, id);
            var before = f.task(id);
            f.api.sql("CREATE FUNCTION test_replay_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'test-only-replay-audit-failure'; END $$");
            f.api.sql("CREATE TRIGGER test_replay_audit_failure BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION test_replay_audit_failure()");
            var request = body("audit-rollback-key", 1, "authorized-replay");
            var failed = replay(f, id, request, OPERATOR_A);
            assertEquals(503, failed.statusCode(), failed.body());
            assertFalse(failed.body().contains("test-only-replay-audit-failure"));
            assertEquals(before, f.task(id));
            assertEquals(0, count(f, "replay_request", id)); assertEquals(0, count(f, "audit_event", id));
            f.api.sql("DROP TRIGGER test_replay_audit_failure ON audit_event");
            assertEquals(200, replay(f, id, request, OPERATOR_A).statusCode());
            assertEquals(1, count(f, "replay_request", id)); assertEquals(1, count(f, "audit_event", id));
        }
    }

    @Test
    void replayRejectsUnauthorizedCallersNonfailedStatesAndStaleRoundsWithoutMutation() throws Exception {
        var status = new AtomicInteger(400);
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, status.get(), "result"));
            var id = f.submit("replay-access", "{}");
            assertEquals(409, replay(f, id, body("pending", 1, "reason"), OPERATOR_A).statusCode());
            var lease = f.repository.claim("cp04-provider", "replay-processing", Duration.ofMillis(300)).orElseThrow();
            assertEquals(409, replay(f, id, body("processing", 1, "reason"), OPERATOR_A).statusCode());
            f.awaitExpiredLease(id, Duration.ofSeconds(2)); f.repository.recoverExpired(100);
            executeNextAttempt(f, id);
            var failed = f.task(id);
            assertEquals(403, replay(f, id, body("business", 1, "reason"), CALLER_A).statusCode());
            assertEquals(403, replay(f, id, body("config", 1, "reason"), CONFIG_A).statusCode());
            assertEquals(404, replay(f, id, body("other-tenant", 1, "reason"), OPERATOR_B).statusCode());
            assertEquals(404, replay(f, UUID.randomUUID(), body("absent", 1, "reason"), OPERATOR_A).statusCode());
            assertEquals(409, replay(f, id, body("stale", 2, "reason"), OPERATOR_A).statusCode());
            assertEquals(failed, f.task(id)); assertEquals(0, count(f, "replay_request", id));
            assertEquals(200, replay(f, id, body("authorized", 1, "reason"), OPERATOR_A).statusCode());
            status.set(200); executeNextAttempt(f, id);
            var succeeded = f.task(id);
            assertEquals(409, replay(f, id, body("succeeded", 2, "reason"), OPERATOR_A).statusCode());
            assertEquals(succeeded, f.task(id));
            assertFalse(f.repository.reserve(lease, Duration.ofMillis(100)).isPresent());
        }
    }

    @Test
    void oldWorkerWritesCannotChangeTheReplayRoundOrItsNewSuccessfulAttempt() throws Exception {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.put("app.worker.global-concurrency", "2");
        properties.put("app.delivery.max-attempts", "1");
        WorkerFaults faults = null;
        try (var f = new WorkerFixture(properties, WorkerFaults.Configuration.class)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 200, "supplier-applied"));
            faults = f.api.application.getBean(WorkerFaults.class);
            faults.arm(WorkerLifecycleListener.Phase.AFTER_HTTP, true);
            var id = f.submit("replay-old-worker", "{}");
            f.worker.tick(); assertTrue(faults.awaitReached());
            Lease old = faults.observedLease; Attempt oldAttempt = faults.observedAttempt;
            f.awaitExpiredLease(id, Duration.ofSeconds(5));
            assertEquals(1, f.repository.recoverExpired(100));
            assertEquals("failed", f.task(id).path("status").asText());
            assertEquals(200, replay(f, id, body("new-round", 1, "recover-unknown"), OPERATOR_A).statusCode());
            var replayed = f.task(id);
            assertEquals(2, replayed.path("run_number").asInt());
            assertFalse(f.repository.complete(old, oldAttempt, new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now())));
            assertFalse(f.repository.complete(old, oldAttempt, new Completion("UNKNOWN", null, "LATE_ERROR", Instant.now())));
            assertTrue(f.repository.reserve(old, Duration.ofSeconds(1)).isEmpty());
            assertEquals(replayed, f.task(id));
            f.worker.tick();
            var succeeded = f.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            faults.release(); f.awaitIdle(Duration.ofSeconds(3));
            assertEquals(succeeded, f.task(id));
            var history = f.attempts(id);
            assertEquals(2, history.size());
            assertEquals(1, history.get(0).path("run_number").asInt());
            assertEquals("UNKNOWN", history.get(0).path("outcome").asText());
            assertEquals(2, history.get(1).path("run_number").asInt());
            assertEquals(1, history.get(1).path("attempt_no").asInt());
            assertEquals("HTTP_ACKNOWLEDGED", history.get(1).path("outcome").asText());
            assertNotEquals(history.get(0).path("lease_token"), history.get(1).path("lease_token"));
            assertEquals(2, f.mock.journal().size());
            assertArrayEquals(f.mock.journal().get(0).body(), f.mock.journal().get(1).body());
            f.evidence("cp05-old-worker-new-round", id, null);
        } finally { if (faults != null) faults.release(); }
    }

    @Test
    void replayInputIsStrictAndRejectedBodiesCannotCreateAnOperation() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 400, "failed"));
            var id = f.submit("replay-validation", "{}"); executeNextAttempt(f, id);
            var before = f.task(id);
            var invalid = new ArrayList<JsonNode>();
            invalid.add(body("", 1, "reason")); invalid.add(body("x".repeat(129), 1, "reason"));
            invalid.add(body("bad key", 1, "reason")); invalid.add(body("valid", 0, "reason"));
            invalid.add(body("valid", 1, " ")); invalid.add(body("valid", 1, "x".repeat(513)));
            invalid.add(body("valid", 1, "contains\u0000nul")); invalid.add(body("valid", 1, "reason").put("unknown", true));
            invalid.add(body("valid", 1, "reason").put("expectedRunNumber", 1.5));
            for (var request : invalid) assertEquals(400, replay(f, id, request, OPERATOR_A).statusCode(), request.toString());
            assertEquals(before, f.task(id)); assertEquals(0, count(f, "replay_request", id));
            assertEquals(0, count(f, "audit_event", id)); assertEquals(1, f.mock.journal().size());
            var valid = body("x".repeat(128), 1, "😀".repeat(512));
            assertEquals(200, replay(f, id, valid, OPERATOR_A).statusCode());
        }
    }
}
