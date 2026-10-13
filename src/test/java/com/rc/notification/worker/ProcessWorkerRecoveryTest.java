package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.ProcessHarness;
import com.rc.notification.support.ProcessScenario;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP09 TC-WORK-02..06/10, TC-OPS-02: actual child JVM termination and durable recovery. */
@Timeout(150)
class ProcessWorkerRecoveryTest {
    static final Duration WAIT = Duration.ofSeconds(15);
    static final Map<String, Object> SHORT_LEASE = Map.of("app.worker.lease-duration", "4s",
            "app.worker.attempt-timeout", "2s", "app.worker.business-timeout", "1s");

    static Stream<Arguments> killWindows() {
        return Stream.of(Arguments.of(Phase.AFTER_CLAIM, true), Arguments.of(Phase.AFTER_RESERVE, true),
                Arguments.of(Phase.AFTER_REQUEST_STARTED, true), Arguments.of(Phase.AFTER_HTTP, true),
                Arguments.of(Phase.AFTER_HTTP, false));
    }

    @ParameterizedTest @MethodSource("killWindows")
    void aRealKillAtEachDurableBoundaryIsRecoveredByAnotherJvm(Phase phase, boolean supplierDeduplicates) throws Exception {
        Set<String> applied = ConcurrentHashMap.newKeySet(); var effects = new AtomicInteger();
        try (var f = new ProcessScenario(SHORT_LEASE)) {
            f.mock.route("/notify", exchange -> {
                if (!supplierDeduplicates || applied.add(exchange.getRequestHeaders().getFirst("X-Event-Id"))) effects.incrementAndGet();
                HttpMock.respond(exchange, 200, "ok");
            });
            var first = f.startWorker(Map.of()); var survivor = f.startWorker(Map.of());
            assertNotEquals(first.pid(), survivor.pid()); assertNotEquals(ProcessHandle.current().pid(), first.pid());
            UUID id = f.submit("killed-" + phase + "-" + supplierDeduplicates, "{\"value\":7}");
            first.arm(phase, id); assertEquals(1, first.tick());
            var reached = first.awaitPhase(phase, id, WAIT);
            assertTrue(reached.blocked()); assertEquals(first.pid(), reached.pid());
            boolean reserved = phase != Phase.AFTER_CLAIM; boolean sent = phase == Phase.AFTER_HTTP;
            assertEquals("processing", f.task(id).path("status").asText());
            assertEquals(reserved ? 1 : 0, f.task(id).path("run_attempt_count").asInt());
            assertEquals(reserved ? 1 : 0, f.attempts(id).size());
            assertEquals(sent ? 1 : 0, f.mock.journal().size()); assertEquals(sent ? 1 : 0, effects.get());
            String name = "cp09-kill-" + phase.name().toLowerCase().replace('_', '-') + "-" + supplierDeduplicates;
            f.evidence(name + "-before", id, effects.get());
            first.forceKill(); assertFalse(first.isAlive()); assertNotEquals(0, first.exitCode());
            f.evidence(name + "-killed", id, effects.get());
            f.tickUntil(survivor, id, "succeeded", WAIT);
            JsonNode completed = f.task(id); var history = f.attempts(id);
            assertEquals(reserved ? 2 : 1, completed.path("run_attempt_count").asInt());
            assertEquals(reserved ? 2 : 1, completed.path("total_attempt_count").asInt());
            assertEquals(reserved ? 2 : 1, history.size());
            if (reserved) {
                assertEquals("UNKNOWN", history.get(0).path("outcome").asText());
                assertEquals("LEASE_EXPIRED", history.get(0).path("error_code").asText());
                assertFalse(history.get(0).path("ended_at").isNull());
                assertNotEquals(history.get(0).path("lease_token"), history.get(1).path("lease_token"));
            }
            assertEquals("HTTP_ACKNOWLEDGED", history.get(history.size() - 1).path("outcome").asText());
            assertEquals(sent ? 2 : 1, f.mock.journal().size());
            assertEquals(sent && !supplierDeduplicates ? 2 : 1, effects.get());
            for (var request : f.mock.journal()) {
                assertEquals(completed.path("event_id").asText(), request.firstHeader("X-Event-Id"));
                assertArrayEquals(snapshotBody(completed), request.body());
            }
            f.evidence(name + "-recovered", id, effects.get());
            reconcile(f, name, List.of(id), List.of(first, survivor), effects.get());
        }
    }

    static Stream<Arguments> lateCompletions() {
        return Stream.of(Arguments.of(200, false), Arguments.of(503, false), Arguments.of(200, true), Arguments.of(503, true));
    }

    @ParameterizedTest @MethodSource("lateCompletions")
    void aPausedOldProcessCannotOverwriteANewOwnerOrAnOperatorReplay(int oldHttpStatus, boolean replay) throws Exception {
        var status = new AtomicInteger(oldHttpStatus);
        var settings = new java.util.HashMap<String, Object>(SHORT_LEASE);
        if (replay) settings.put("app.delivery.max-attempts", "1");
        try (var f = new ProcessScenario(settings)) {
            f.mock.route("/notify", exchange -> HttpMock.respond(exchange, status.get(), "result"));
            var old = f.startWorker(Map.of()); var replacement = f.startWorker(Map.of());
            UUID id = f.submit("late-result-" + oldHttpStatus + "-" + replay, "{}");
            old.arm(Phase.AFTER_HTTP, id); old.tick(); old.awaitPhase(Phase.AFTER_HTTP, id, WAIT);
            assertEquals(1, f.mock.journal().size()); f.awaitExpiredLease(id, WAIT); status.set(200);
            if (replay) {
                f.tickUntil(replacement, id, "failed", WAIT);
                assertEquals("UNKNOWN", f.task(id).path("last_outcome").asText());
                var body = JSON.createObjectNode().put("replayRequestId", "late-old-owner")
                        .put("expectedRunNumber", 1).put("reason", "Recover an expired worker lease");
                var response = f.send("POST", "/admin/notifications/" + id + "/replay", body, OPERATOR_A);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(2, JSON.readTree(response.body()).path("runNumber").asInt());
            }
            f.tickUntil(replacement, id, "succeeded", WAIT);
            var durable = f.task(id); var history = f.attempts(id);
            String name = "cp09-late-completion-" + oldHttpStatus + "-replay-" + replay;
            f.evidence(name + "-before-release", id, null);
            old.release(); old.awaitIdle(WAIT);
            assertEquals(durable, f.task(id), "A stale success, failure, or retry schedule must change no task field");
            assertEquals(history, f.attempts(id), "The old attempt must not regain ownership");
            assertEquals(replay ? 2 : 1, durable.path("run_number").asInt());
            assertEquals(replay ? 1 : 2, durable.path("run_attempt_count").asInt());
            assertEquals(2, durable.path("total_attempt_count").asInt());
            assertEquals(2, f.mock.journal().size());
            assertTrue(Files.readAllLines(old.logPath()).stream().anyMatch(line -> line.contains("delivery_completion")
                    && line.contains("notificationId=" + id) && line.contains("committed=false") && line.contains("STALE_OWNER")));
            f.evidence(name + "-after-release", id, null);
            reconcile(f, name, List.of(id), List.of(old, replacement), null);
        }
    }

    @Test
    void anExpiredClaimCannotReserveOrSendAfterTheReplacementHasFinished() throws Exception {
        try (var f = new ProcessScenario(SHORT_LEASE)) {
            f.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "ok"));
            var old = f.startWorker(Map.of()); var replacement = f.startWorker(Map.of());
            UUID id = f.submit("late-claim", "{}"); old.arm(Phase.AFTER_CLAIM, id); old.tick();
            old.awaitPhase(Phase.AFTER_CLAIM, id, WAIT);
            assertEquals(0, f.task(id).path("run_attempt_count").asInt()); assertTrue(f.attempts(id).isEmpty());
            f.awaitExpiredLease(id, WAIT); f.tickUntil(replacement, id, "succeeded", WAIT);
            var state = f.task(id); var attempts = f.attempts(id);
            old.release(); old.awaitIdle(WAIT);
            assertEquals(state, f.task(id)); assertEquals(attempts, f.attempts(id));
            assertEquals(1, attempts.size()); assertEquals(1, f.mock.journal().size());
            f.evidence("cp09-expired-claim-fenced", id, null);
            reconcile(f, "cp09-expired-claim-fenced", List.of(id), List.of(old, replacement), null);
        }
    }

    @Test
    void mixedAcceptedTasksSurviveKillingAndRestartingEachWorkerInTurn() throws Exception {
        var arrivals = new ConcurrentHashMap<String, AtomicInteger>(); var effects = ConcurrentHashMap.<String>newKeySet();
        try (var f = new ProcessScenario(SHORT_LEASE)) {
            f.mock.route("/notify", exchange -> {
                String event = exchange.getRequestHeaders().getFirst("X-Event-Id");
                int number = arrivals.computeIfAbsent(event, ignored -> new AtomicInteger()).incrementAndGet();
                int status = event.contains("retry") && number == 1 ? 500 : 200;
                if (status == 200) effects.add(event);
                HttpMock.respond(exchange, status, "mixed");
            });
            var workers = new ProcessHarness[] {f.startWorker(Map.of()), f.startWorker(Map.of())};
            var allProcesses = new ArrayList<ProcessHarness>(List.of(workers)); var accepted = new ArrayList<UUID>();
            for (int victimIndex = 0; victimIndex < 2; victimIndex++) {
                var victim = workers[victimIndex]; var survivor = workers[1 - victimIndex];
                UUID interrupted = f.submit("interrupted-worker-" + victimIndex, "{}"); accepted.add(interrupted);
                victim.arm(Phase.AFTER_REQUEST_STARTED, interrupted); victim.tick();
                victim.awaitPhase(Phase.AFTER_REQUEST_STARTED, interrupted, WAIT);
                victim.forceKill(); assertFalse(victim.isAlive());
                survivor.startPolling(Duration.ofMillis(30));
                var batch = new ArrayList<UUID>();
                for (int item = 0; item < 6; item++) {
                    UUID id = f.submit("round-" + victimIndex + "-" + (item % 2 == 0 ? "retry-" : "ok-") + item, "{}");
                    batch.add(id); accepted.add(id);
                }
                for (UUID id : batch) f.awaitStatus(id, "succeeded", WAIT);
                f.awaitStatus(interrupted, "succeeded", WAIT); survivor.stopPolling(); survivor.awaitIdle(WAIT);
                workers[victimIndex] = f.startWorker(Map.of()); allProcesses.add(workers[victimIndex]);
                assertNotEquals(victim.pid(), workers[victimIndex].pid());
                for (UUID id : accepted) f.evidence("cp09-swap-kill-round-" + victimIndex, id, effects.size());
            }
            assertEquals(14, accepted.size()); assertEquals(14, effects.size()); assertEquals(20, f.mock.journal().size());
            for (UUID id : accepted) {
                var task = f.task(id); var history = f.attempts(id);
                String event = task.path("event_id").asText();
                assertEquals(event.contains("retry") ? 2 : 1, arrivals.get(event).get());
                assertEquals(event.contains("retry") ? 2 : 1,
                        f.mock.journal().stream().filter(call -> event.equals(call.firstHeader("X-Event-Id"))).count());
                int expected = task.path("event_id").asText().contains("retry") || task.path("event_id").asText().startsWith("interrupted") ? 2 : 1;
                assertEquals(expected, history.size()); assertEquals(expected, task.path("total_attempt_count").asInt());
                assertEquals("HTTP_ACKNOWLEDGED", history.get(history.size() - 1).path("outcome").asText());
                assertEquals(history.size(), history.stream().map(a -> a.path("lease_token").asText()).distinct().count());
            }
            reconcile(f, "cp09-swap-kill", accepted, allProcesses, effects.size());
        }
    }

    @Test
    void sigtermCancelsWithinItsGracePeriodAndUtcTasksRecoverAcrossDifferentTimezones() throws Exception {
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger();
        try (var f = new ProcessScenario(Map.of("app.worker.global-concurrency", "1", "app.worker.provider-concurrency", "1",
                "app.worker.lease-duration", "12s", "app.worker.attempt-timeout", "10s", "app.worker.business-timeout", "8s",
                "app.worker.shutdown-grace-period", "100ms", "spring.datasource.hikari.connection-init-sql", "SET TIME ZONE 'Asia/Tokyo'"))) {
            f.mock.route("/notify", exchange -> {
                if (count.incrementAndGet() == 1) {
                    arrived.countDown();
                    try { if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("Unreleased first HTTP"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
                }
                try { HttpMock.respond(exchange, 200, "ok"); } catch (java.io.IOException cancelledConnection) { exchange.close(); }
            });
            var stopping = f.startWorker(Map.of("app.worker.scheduling-enabled", "true", "app.worker.poll-interval", "30ms"),
                    Map.of("JAVA_TOOL_OPTIONS", "-Duser.timezone=Pacific/Honolulu"));
            var survivor = f.startWorker(Map.of());
            assertEquals("Pacific/Honolulu", stopping.state().path("javaTimezone").asText());
            assertEquals("Asia/Tokyo", stopping.databaseObservation().path("timezone").asText());
            UUID inFlight = f.submit("graceful-in-flight", "{}"); assertTrue(arrived.await(5, TimeUnit.SECONDS));
            UUID pending = f.submit("graceful-pending", "{}");
            long started = System.nanoTime(); stopping.terminateGracefully(Duration.ofSeconds(8));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0);
            assertFalse(stopping.isAlive()); assertEquals(1, f.mock.journal().size(), "SIGTERM must not claim the pending task");
            assertEquals(0, f.task(pending).path("run_attempt_count").asInt());
            release.countDown(); survivor.startPolling(Duration.ofMillis(30));
            for (UUID id : List.of(inFlight, pending)) {
                f.awaitStatus(id, "succeeded", Duration.ofSeconds(20));
                var detail = f.get("/v1/notifications/" + id, ADMIN); assertEquals(200, detail.statusCode());
                var view = JSON.readTree(detail.body());
                for (String field : List.of("createdAt", "updatedAt", "deadlineAt")) {
                    assertTrue(view.path(field).asText().endsWith("Z"), field); Instant.parse(view.path(field).asText());
                }
                f.evidence("cp09-sigterm-timezones", id, null);
            }
            survivor.stopPolling(); survivor.awaitIdle(WAIT);
            assertEquals(2, f.attempts(inFlight).size()); assertEquals("UNKNOWN", f.attempts(inFlight).get(0).path("outcome").asText());
            assertEquals(1, f.attempts(pending).size());
            reconcile(f, "cp09-sigterm-timezones", List.of(inFlight, pending), List.of(stopping, survivor), null);
        } finally { release.countDown(); }
    }

    static byte[] snapshotBody(JsonNode task) { return HexFormat.of().parseHex(task.path("body_bytes").asText().substring(2)); }

    /** All accepted IDs must reconcile to durable terminal tasks; no per-task sample is substituted for the set. */
    static void reconcile(ProcessScenario f, String scenario, List<UUID> ids, List<ProcessHarness> processes, Integer effects) throws Exception {
        assertEquals(ids.size(), Set.copyOf(ids).size()); assertEquals(ids.size(), f.taskCount());
        var evidence = JSON.createObjectNode().put("scenario", scenario + "-summary").put("observedAt", Instant.now().toString())
                .put("acceptedCount", ids.size()).put("durableTaskCount", f.taskCount()).put("httpCalls", f.mock.journal().size());
        var accepted = evidence.putArray("acceptedTaskIds");
        for (UUID id : ids) { assertEquals("succeeded", f.task(id).path("status").asText()); accepted.add(id.toString()); }
        evidence.put("succeededCount", ids.size()); var pids = evidence.putArray("processIds"); processes.forEach(process -> pids.add(process.pid()));
        if (effects != null) evidence.put("sideEffects", effects);
        Path directory = Path.of("target", "evidence"); Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario + "-summary.json").toFile(), evidence);
    }
}
