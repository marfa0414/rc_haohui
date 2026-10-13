package com.rc.notification.process;

import static com.rc.notification.support.NotificationApiFixture.JSON;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.JvmClockSkew;
import com.rc.notification.support.ProcessScenario;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TC-WORK-10: actual java.time skew in separate JVMs; OS time, PostgreSQL and monotonic clocks stay real. */
@Timeout(100)
class ProcessClockSkewTest {
    @ParameterizedTest @ValueSource(longs = {-3600, 3600})
    void databaseTimeOwnsLeasesDeadlinesAndLateWriteFencingDespiteJavaTimeSkew(long offset) throws Exception {
        Map<String, Object> settings = Map.of(JvmClockSkew.PROPERTY, Long.toString(offset),
                "app.worker.lease-duration", "3s", "app.worker.attempt-timeout", "2s", "app.worker.business-timeout", "1s",
                "app.worker.global-concurrency", "1", "app.delivery.run-duration", "6s");
        try (var scenario = new ProcessScenario(settings)) {
            scenario.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "acknowledged"));
            var owner = scenario.startWorker(Map.of());
            var replacement = scenario.startWorker(Map.of(JvmClockSkew.PROPERTY, Long.toString(-offset)));
            JsonNode apiClock = scenario.api.state(), ownerClock = owner.state(), replacementClock = replacement.state();
            assertOffset(apiClock, offset); assertOffset(ownerClock, offset); assertOffset(replacementClock, -offset);
            JsonNode childDatabase = owner.databaseObservation();
            assertTrue(Math.abs(Duration.between(Instant.now(), Instant.parse(childDatabase.path("now").asText())).toMillis()) < 2000);
            Instant acceptedAfter = databaseNow(scenario);
            UUID taskId = scenario.submit("clock-skew-" + offset, "{}");
            JsonNode accepted = scenario.task(taskId);
            assertDatabaseInstant(instant(accepted, "created_at"), acceptedAfter, databaseNow(scenario));
            assertEquals(Duration.ofSeconds(6), Duration.between(instant(accepted, "created_at"), instant(accepted, "deadline_at")));

            owner.arm(Phase.AFTER_HTTP, taskId);
            Instant claimAfter = databaseNow(scenario);
            assertEquals(1, owner.tick());
            var boundary = owner.awaitPhase(Phase.AFTER_HTTP, taskId, Duration.ofSeconds(4));
            assertTrue(boundary.blocked());
            JsonNode inFlight = scenario.task(taskId);
            Instant checkedAt = databaseNow(scenario);
            assertEquals("processing", inFlight.path("status").asText());
            assertTrue(instant(inFlight, "lease_until").isAfter(checkedAt));
            assertTrue(Duration.between(claimAfter, instant(inFlight, "lease_until")).compareTo(Duration.ofSeconds(4)) < 0);
            JsonNode firstAttempt = scenario.attempts(taskId).get(0);
            assertDatabaseInstant(instant(firstAttempt, "started_at"), claimAfter, checkedAt);
            assertDatabaseInstant(instant(firstAttempt, "request_started_at"), claimAfter, checkedAt);
            assertEquals(Duration.ofSeconds(2), Duration.between(instant(firstAttempt, "started_at"), instant(firstAttempt, "execution_deadline")));
            assertEquals(1, scenario.mock.count("/notify"));
            assertEquals(0, replacement.tick(), "A wall clock one hour ahead still cannot take an unexpired database lease");
            scenario.awaitExpiredLease(taskId, Duration.ofSeconds(5));
            scenario.tickUntil(replacement, taskId, "succeeded", Duration.ofSeconds(2));
            JsonNode completed = scenario.task(taskId);
            List<JsonNode> committedAttempts = scenario.attempts(taskId);
            assertEquals(2, committedAttempts.size());
            assertEquals("UNKNOWN", committedAttempts.get(0).path("outcome").asText());
            assertEquals("HTTP_ACKNOWLEDGED", committedAttempts.get(1).path("outcome").asText());
            assertEquals(2, scenario.mock.count("/notify"));
            // This timestamp is diagnostic and genuinely follows the skewed JVM clock; it is never a lease authority.
            assertTrue(Math.abs(Duration.between(databaseNow(scenario), instant(committedAttempts.get(1), "http_send_started_at")).toSeconds() + offset) < 3);
            owner.release(); owner.awaitIdle(Duration.ofSeconds(3));
            assertEquals(completed, scenario.task(taskId)); assertEquals(committedAttempts, scenario.attempts(taskId));
            assertTrue(Files.readString(owner.logPath()).contains("committed=false"));

            UUID expires = scenario.submit("clock-skew-deadline-" + offset, "{}");
            awaitDeadline(scenario, expires, Duration.ofSeconds(8));
            assertEquals(0, replacement.tick()); replacement.awaitIdle(Duration.ofSeconds(2));
            JsonNode expired = scenario.task(expires);
            assertEquals("failed", expired.path("status").asText());
            assertEquals("DEADLINE_EXCEEDED", expired.path("defer_reason").asText());
            assertEquals(0, expired.path("run_attempt_count").asInt()); assertTrue(scenario.attempts(expires).isEmpty());
            assertEquals(2, scenario.mock.count("/notify"));

            String suffix = offset < 0 ? "minus-hour" : "plus-hour";
            scenario.evidence("cp09-java-time-" + suffix, taskId, null);
            scenario.evidence("cp09-java-time-deadline-" + suffix, expires, null);
            var evidence = JSON.createObjectNode().put("scenario", "cp09-java-time-observation-" + suffix)
                    .put("injectionLayer", "java.time.Clock.currentInstant only; OS and System.nanoTime unchanged")
                    .put("databaseNow", databaseNow(scenario).toString()).put("leaseAndDeadlineUseDatabaseTime", true)
                    .put("oldOwnerWriteCommitted", false).put("businessRequests", 2);
            evidence.set("apiClock", apiClock); evidence.set("ownerClock", ownerClock); evidence.set("replacementClock", replacementClock);
            evidence.set("childDatabase", childDatabase);
            Path file = Path.of("target", "evidence", "cp09-java-time-observation-" + suffix + ".json");
            Files.createDirectories(file.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), evidence);
        }
    }
    private static void assertOffset(JsonNode state, long seconds) {
        assertEquals("java.time.Clock.currentInstant", state.path("clockInjectionLayer").asText());
        assertEquals(seconds, state.path("clockOffsetSeconds").asLong());
        assertTrue(Math.abs(state.path("measuredInstantOffsetMillis").asLong() - seconds * 1000) < 1000);
        assertTrue(Math.abs(state.path("measuredSystemClockOffsetMillis").asLong() - seconds * 1000) < 1000);
        assertTrue(Math.abs(state.path("systemCurrentTimeMillis").asLong() - System.currentTimeMillis()) < 3000,
                "Parent and child OS clocks must remain unchanged");
    }
    private static Instant databaseNow(ProcessScenario scenario) throws Exception {
        try (var connection = scenario.database.connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(rows.next()); return rows.getTimestamp(1).toInstant();
        }
    }
    private static Instant instant(JsonNode row, String field) { return Instant.parse(row.path(field).asText()); }
    private static void assertDatabaseInstant(Instant value, Instant lower, Instant upper) {
        assertFalse(value.isBefore(lower)); assertFalse(value.isAfter(upper));
    }
    private static void awaitDeadline(ProcessScenario scenario, UUID taskId, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        do {
            try (var connection = scenario.database.connection(); var statement = connection.prepareStatement(
                    "SELECT deadline_at<=clock_timestamp() FROM " + scenario.database.schema() + ".notification_task WHERE id=?")) {
                statement.setObject(1, taskId); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); if (rows.getBoolean(1)) return; }
            }
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        fail("Database deadline did not expire");
    }
}
