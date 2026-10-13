package com.rc.notification.process;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.ProcessScenario.request;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.ProcessHarness;
import com.rc.notification.support.ProcessScenario;
import com.rc.notification.support.TcpFaultProxy;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real child JVMs and severed PostgreSQL sockets; the shared PostgreSQL server is never stopped. */
@Timeout(180)
class ProcessDatabaseRecoveryTest {
    @Test
    void acknowledgedHttpWithLostDatabaseWritebackRemainsUnknownUntilLeaseRecoveryInAnotherJvm() throws Exception {
        try (var f = new ProcessScenario(); var databaseLink = new TcpFaultProxy(f.database.jdbcUrl())) {
            Set<String> applied = ConcurrentHashMap.newKeySet(); var effects = new AtomicInteger();
            f.mock.route("/notify", exchange -> {
                if (applied.add(exchange.getRequestHeaders().getFirst("X-Event-Id"))) effects.incrementAndGet();
                HttpMock.respond(exchange, 200, "acknowledged");
            });
            var subject = f.start("all", Map.of("spring.datasource.url", databaseLink.jdbcUrl()), Map.of());
            var id = f.submit("http-ack-database-write-lost", "{}");
            subject.arm(Phase.AFTER_HTTP, id); assertEquals(1, subject.tick());
            var observed = subject.awaitPhase(Phase.AFTER_HTTP, id, Duration.ofSeconds(8));
            assertEquals(1, f.mock.journal().size()); assertEquals(1, effects.get());
            assertEquals("REQUEST_STARTED", f.attempts(id).get(0).path("phase").asText());
            databaseLink.cut(); Instant disconnected = Instant.now(); subject.release(); subject.awaitIdle(Duration.ofSeconds(5));
            assertEquals(503, request(subject, "GET", "/health/ready", null, null).statusCode());
            assertEquals(200, request(subject, "GET", "/health/live", null, null).statusCode());
            var outageSubmission = request(subject, "POST", "/v1/notifications",
                    JSON.readTree(payload("tenant-a", "worker.notify", "must-not-be-accepted-during-outage", "{}")), ADMIN);
            assertEquals(503, outageSubmission.statusCode(), outageSubmission.body()); assertEquals(1, f.taskCount());
            var notCommitted = f.task(id); assertEquals("processing", notCommitted.path("status").asText());
            assertEquals(1, notCommitted.path("run_attempt_count").asInt()); assertTrue(notCommitted.path("last_http_status").isNull());
            var incomplete = f.attempts(id).get(0); assertEquals("REQUEST_STARTED", incomplete.path("phase").asText());
            assertEquals("UNKNOWN", incomplete.path("outcome").asText()); assertTrue(incomplete.path("ended_at").isNull());
            assertTrue(Files.readString(subject.logPath()).contains("delivery_storage_unavailable"), "The actual failed commit path must be recorded safely");
            f.evidence("cp09-db-writeback-not-committed", id, effects.get());
            long originalPid = subject.pid(); subject.forceKill(); assertFalse(subject.isAlive());
            databaseLink.restore(); Instant restored = Instant.now();
            var successor = f.startWorker(Map.of("spring.datasource.url", databaseLink.jdbcUrl())); assertNotEquals(originalPid, successor.pid());
            f.awaitExpiredLease(id, Duration.ofSeconds(12));
            var terminal = f.tickUntil(successor, id, "succeeded", Duration.ofSeconds(8));
            assertEquals(2, terminal.path("run_attempt_count").asInt()); assertEquals(2, terminal.path("total_attempt_count").asInt());
            var attempts = f.attempts(id); assertEquals(2, attempts.size());
            assertEquals("UNKNOWN", attempts.get(0).path("outcome").asText()); assertEquals("LEASE_EXPIRED", attempts.get(0).path("error_code").asText());
            assertEquals("COMPLETED", attempts.get(0).path("phase").asText()); assertFalse(attempts.get(0).path("ended_at").isNull());
            assertEquals("HTTP_ACKNOWLEDGED", attempts.get(1).path("outcome").asText()); assertEquals(200, attempts.get(1).path("http_status").asInt());
            assertEquals(2, f.mock.journal().size()); assertEquals(1, effects.get());
            assertEquals(f.mock.journal().get(0).firstHeader("X-Event-Id"), f.mock.journal().get(1).firstHeader("X-Event-Id"));
            assertArrayEquals(f.mock.journal().get(0).body(), f.mock.journal().get(1).body());
            assertEquals(200, request(successor, "GET", "/health/ready", null, null).statusCode());
            f.evidence("cp09-db-writeback-recovered", id, effects.get());
            ProcessAuthRecoveryTest.summary("cp09-db-writeback-recovery", Map.of("originalPid", originalPid, "successorPid", successor.pid(),
                    "interruptedPhase", observed.phase().name(), "databaseDisconnectedAt", disconnected.toString(), "databaseRestoredAt", restored.toString(),
                    "acceptedTasks", 1, "businessRequests", 2, "sideEffectsWithSupplierDeduplication", effects.get(), "outageAcceptanceStatus", outageSubmission.statusCode()), List.of(id));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"api", "worker", "all"})
    void eachRealRoleSeparatesLivenessFromDatabaseSchemaAndSupplierReadiness(String role) throws Exception {
        try (var f = new ProcessScenario(Map.of("app.delivery.max-attempts", "1")); var databaseLink = new TcpFaultProxy(f.database.jdbcUrl())) {
            f.mock.route("/notify", exchange -> HttpMock.respond(exchange, 503, "supplier unavailable"));
            var subject = f.start(role, Map.of("spring.datasource.url", databaseLink.jdbcUrl()), Map.of());
            assertEquals(!role.equals("api"), subject.state().path("workerEnabled").asBoolean());
            assertEquals(!role.equals("worker"), subject.state().path("apiEnabled").asBoolean());
            assertHealth(subject, 200);
            var id = f.submit("health-supplier-error-" + role, "{}");
            var acceptedTaskIds = new ArrayList<>(List.of(id));
            ProcessHarness worker = role.equals("api") ? f.startWorker(Map.of()) : subject;
            f.tickUntil(worker, id, "failed", Duration.ofSeconds(8));
            assertEquals(503, f.task(id).path("last_http_status").asInt()); assertEquals(1, f.mock.journal().size());
            assertHealth(subject, 200); // Supplier failure is not a dependency of API/worker readiness.

            databaseLink.cut(); Instant disconnected = Instant.now();
            assertHealth(subject, 503); assertTrue(subject.isAlive());
            var requestBody = JSON.readTree(payload("tenant-a", "worker.notify", "health-outage-" + role, "{}"));
            var outage = request(subject, "POST", "/v1/notifications", requestBody, ADMIN);
            assertEquals(role.equals("worker") ? 404 : 503, outage.statusCode(), outage.body());
            assertEquals(1, f.taskCount(), "An unreachable database cannot yield a fictitious accepted row");
            databaseLink.restore(); awaitHealth(subject, 200); Instant restored = Instant.now();
            var afterRestore = request(subject, "POST", "/v1/notifications", requestBody, ADMIN);
            if (role.equals("worker")) assertEquals(404, afterRestore.statusCode());
            else {
                assertEquals(202, afterRestore.statusCode(), afterRestore.body());
                var same = request(subject, "POST", "/v1/notifications", requestBody, ADMIN);
                assertEquals(200, same.statusCode()); assertEquals(JSON.readTree(afterRestore.body()).path("notificationId"), JSON.readTree(same.body()).path("notificationId"));
                var accepted = UUID.fromString(JSON.readTree(afterRestore.body()).path("notificationId").asText());
                acceptedTaskIds.add(accepted);
                f.tickUntil(worker, accepted, "failed", Duration.ofSeconds(8)); f.evidence("cp09-health-accepted-after-recovery-" + role, accepted, null);
            }
            assertHealth(subject, 200);

            f.sql("ALTER TABLE notification_attempt RENAME TO cp09_hidden_attempt");
            try { assertHealth(subject, 503); }
            finally { f.sql("ALTER TABLE cp09_hidden_attempt RENAME TO notification_attempt"); }
            awaitHealth(subject, 200);
            f.sql("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='6'");
            try { assertHealth(subject, 503); }
            finally { f.sql("UPDATE flyway_schema_history SET checksum=checksum-1 WHERE version='6'"); }
            awaitHealth(subject, 200);
            assertEquals("failed", f.task(id).path("status").asText()); assertEquals("HTTP_REJECTED", f.task(id).path("last_outcome").asText());
            f.evidence("cp09-health-role-" + role, id, null);
            ProcessAuthRecoveryTest.summary("cp09-health-role-" + role, Map.of("role", role, "pid", subject.pid(),
                    "databaseDisconnectedAt", disconnected.toString(), "databaseRestoredAt", restored.toString(), "outageSubmissionStatus", outage.statusCode(),
                    "supplierFailureReadyStatus", 200, "databaseFailureReadyStatus", 503, "missingTableReadyStatus", 503,
                    "invalidMigrationReadyStatus", 503, "livenessStayedUp", true), acceptedTaskIds);
        }
    }

    @Test
    void terminalTasksAndAttemptHistorySurviveNewJvmTimezonesWithUtcApiTimestamps() throws Exception {
        var properties = Map.<String, Object>of("app.delivery.max-attempts", "1", "spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'America/New_York'");
        try (var f = new ProcessScenario(properties, Map.of("TZ", "Asia/Tokyo"))) {
            f.mock.route("/notify", exchange -> HttpMock.respond(exchange,
                    exchange.getRequestHeaders().getFirst("X-Event-Id").equals("utc-failed") ? 503 : 200, "ignored"));
            var original = f.startWorker(Map.of()); assertEquals("Asia/Tokyo", original.state().path("javaTimezone").asText());
            String originalDatabaseZone = original.databaseObservation().path("timezone").asText();
            assertEquals("America/New_York", originalDatabaseZone);
            var succeeded = f.submit("utc-succeeded", "{}"); var failed = f.submit("utc-failed", "{}");
            f.tickUntil(original, succeeded, "succeeded", Duration.ofSeconds(8)); f.tickUntil(original, failed, "failed", Duration.ofSeconds(8));
            var beforeSucceeded = apiTask(f, succeeded); var beforeFailed = apiTask(f, failed);
            assertUtc(beforeSucceeded); assertUtc(beforeFailed);
            long workerPid = original.pid(), apiPid = f.api.pid(); original.forceKill(); f.api.forceKill();
            f.api = f.start("api", Map.of(), Map.of("TZ", "Pacific/Honolulu"));
            var restarted = f.startWorker(Map.of(), Map.of("TZ", "Pacific/Honolulu"));
            assertNotEquals(workerPid, restarted.pid()); assertNotEquals(apiPid, f.api.pid());
            assertEquals("Pacific/Honolulu", restarted.state().path("javaTimezone").asText());
            String restartedDatabaseZone = restarted.databaseObservation().path("timezone").asText();
            assertEquals("America/New_York", restartedDatabaseZone);
            for (int n = 0; n < 3; n++) { assertEquals(0, restarted.tick()); restarted.awaitIdle(Duration.ofSeconds(2)); }
            var afterSucceeded = apiTask(f, succeeded); var afterFailed = apiTask(f, failed);
            assertEquals(beforeSucceeded, afterSucceeded); assertEquals(beforeFailed, afterFailed); assertUtc(afterSucceeded); assertUtc(afterFailed);
            assertEquals("succeeded", afterSucceeded.path("status").asText()); assertEquals("failed", afterFailed.path("status").asText());
            assertEquals("HTTP_REJECTED", afterFailed.path("lastOutcome").asText()); assertEquals(503, afterFailed.path("lastHttpStatus").asInt());
            assertEquals(1, f.attempts(succeeded).size()); assertEquals(1, f.attempts(failed).size()); assertEquals(2, f.taskCount()); assertEquals(2, f.mock.journal().size());
            var history = f.get("/v1/notifications/" + failed + "/attempts", ADMIN); assertEquals(200, history.statusCode());
            var item = JSON.readTree(history.body()).path("items").get(0); assertNotNull(item); assertEquals("HTTP_REJECTED", item.path("outcome").asText());
            for (String key : List.of("startedAt", "requestStartedAt", "httpSendStartedAt", "endedAt")) {
                assertTrue(item.path(key).isTextual(), "An actual completed HTTP attempt must expose its timestamp " + key);
                assertTrue(item.path(key).asText().endsWith("Z")); Instant.parse(item.path(key).asText());
            }
            f.evidence("cp09-terminal-utc-succeeded", succeeded, null); f.evidence("cp09-terminal-utc-failed", failed, null);
            ProcessAuthRecoveryTest.summary("cp09-terminal-timezone-restart", Map.of("oldWorkerPid", workerPid, "newWorkerPid", restarted.pid(),
                    "oldApiPid", apiPid, "newApiPid", f.api.pid(), "oldJvmTimezone", "Asia/Tokyo", "newJvmTimezone", "Pacific/Honolulu",
                    "observedJdbcSessionTimezones", List.of(originalDatabaseZone, restartedDatabaseZone), "preservedTerminalTasks", 2, "physicalBusinessRequests", 2, "apiTimestampsUtc", true), List.of(succeeded, failed));
        }
    }

    private static JsonNode apiTask(ProcessScenario f, UUID id) throws Exception {
        var response = f.get("/v1/notifications/" + id, ADMIN); assertEquals(200, response.statusCode()); return JSON.readTree(response.body());
    }
    private static void assertUtc(JsonNode task) {
        for (String key : List.of("createdAt", "updatedAt", "nextAttemptAt", "deadlineAt")) {
            if (key.equals("nextAttemptAt") && task.path(key).isNull()) continue;
            assertTrue(task.path(key).isTextual(), "Missing timestamp " + key); String value = task.path(key).asText();
            assertTrue(value.endsWith("Z"), "API timestamp must be UTC: " + key); Instant.parse(value);
        }
    }
    private static void assertHealth(ProcessHarness process, int ready) throws Exception {
        var liveness = request(process, "GET", "/health/live", null, null);
        assertEquals(200, liveness.statusCode()); assertEquals("UP", JSON.readTree(liveness.body()).path("status").asText());
        var readiness = request(process, "GET", "/health/ready", null, null);
        assertEquals(ready, readiness.statusCode(), readiness.body()); assertEquals(ready == 200 ? "UP" : "DOWN", JSON.readTree(readiness.body()).path("status").asText());
    }
    private static void awaitHealth(ProcessHarness process, int ready) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do { if (request(process, "GET", "/health/ready", null, null).statusCode() == ready) { assertHealth(process, ready); return; } Thread.sleep(25); }
        while (System.nanoTime() < end);
        assertHealth(process, ready);
    }
}
