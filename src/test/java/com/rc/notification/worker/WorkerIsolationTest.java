package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.ConfigurationFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP07 TC-GUARD-05/08; fixed TEST_CASES §2.5 workload and full twenty-second observation. */
@Timeout(65)
class WorkerIsolationTest {
    @Test
    void thirtyTimeoutsCannotStarveTenHealthyRequestsWithDefaultTenAndThreeLimits() throws Exception {
        var releaseSlow = new CountDownLatch(1);
        try (var healthy = new HttpMock(); var f = new WorkerFixture(Map.of(
                "app.worker.global-concurrency", "10", "app.worker.provider-concurrency", "3",
                "app.worker.poll-interval", "100ms", "app.worker.business-timeout", "2s",
                "app.worker.attempt-timeout", "4s", "app.worker.lease-duration", "12s",
                "app.worker.shutdown-grace-period", "0s"), List.of(healthy.uri("/healthy").getPort()))) {
            f.mock.route("/notify", exchange -> {
                try { releaseSlow.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            healthy.route("/healthy", exchange -> {
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                HttpMock.respond(exchange, 200, "ok");
            });
            var template = template("tenant-a", "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\"}}"))
                    .put("providerId", "healthy-provider").put("url", healthy.uri("/healthy").toString());
            template.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\"}}"));
            f.configuration.install(template, apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET"), "healthy.notify");
            List<UUID> slowIds = new ArrayList<>(); List<UUID> healthyIds = new ArrayList<>();
            for (int n = 0; n < 30; n++) slowIds.add(f.submit("isolation-slow-" + n, "{}"));
            for (int n = 0; n < 10; n++) {
                var response = f.api.post(payload("tenant-a", "healthy.notify", "isolation-healthy-" + n, "{}"), ADMIN);
                assertEquals(202, response.statusCode(), response.body()); healthyIds.add(UUID.fromString(f.api.json(response).path("notificationId").asText()));
            }
            assertEquals(40, f.api.taskCount()); assertEquals(0, f.mock.journal().size()); assertEquals(0, healthy.journal().size());
            Instant startedAt = Instant.now(); long startedNanos = System.nanoTime();
            f.restart(Map.of("app.worker.scheduling-enabled", "true"));
            var evidence = JSON.createObjectNode().put("scenario", "cp07-fixed-supplier-isolation").put("startedAt", startedAt.toString())
                    .put("globalLimit", 10).put("providerLimit", 3).put("queueLimit", 10).put("pollMillis", 100)
                    .put("businessTimeoutMillis", 2000).put("slowSubmitted", 30).put("healthySubmitted", 10);
            var timeline = evidence.putArray("timeline");
            int globalPeak = 0, slowPeak = 0, healthyPeak = 0, queuePeak = 0, backlogPeak = 40;
            long allHealthySucceededMillis = -1;
            while (System.nanoTime() - startedNanos < Duration.ofSeconds(20).toNanos()) {
                long at = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
                int global = f.worker.activeCount(), slow = f.worker.activeCount("cp04-provider"), fast = f.worker.activeCount("healthy-provider"), queued = f.worker.queuedCount();
                globalPeak = Math.max(globalPeak, global); slowPeak = Math.max(slowPeak, slow); healthyPeak = Math.max(healthyPeak, fast); queuePeak = Math.max(queuePeak, queued);
                assertTrue(global <= 10, "Global owned work exceeded ten"); assertTrue(slow <= 3, "Slow provider exceeded three");
                assertTrue(fast <= 3, "Healthy provider exceeded three"); assertTrue(queued <= 10, "Process execution queue exceeded its explicit bound");
                int succeeded = 0, backlog = 0;
                try (var connection = f.api.database.connection(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT count(*) FILTER (WHERE provider_id='healthy-provider' AND status='succeeded'), count(*) FILTER (WHERE status IN ('pending','processing')) FROM "
                                + f.api.database.schema() + ".notification_task")) {
                    assertTrue(rows.next()); succeeded = rows.getInt(1); backlog = rows.getInt(2);
                }
                backlogPeak = Math.max(backlogPeak, backlog);
                if (succeeded == 10 && allHealthySucceededMillis < 0) allHealthySucceededMillis = at;
                timeline.addObject().put("atMillis", at).put("globalActive", global).put("slowActive", slow).put("healthyActive", fast)
                        .put("queued", queued).put("databaseBacklog", backlog).put("healthySucceeded", succeeded);
                Thread.sleep(100);
            }
            evidence.put("observedMillis", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis())
                    .put("globalPeak", globalPeak).put("slowPeak", slowPeak).put("healthyPeak", healthyPeak).put("queuePeak", queuePeak)
                    .put("backlogPeak", backlogPeak).put("allHealthySucceededMillis", allHealthySucceededMillis);
            var calls = evidence.putArray("healthyRequests");
            for (var call : healthy.journal()) calls.addObject().put("eventId", call.firstHeader("X-Event-Id"))
                    .put("receivedAt", call.receivedAt().toString()).put("startDelayMillis", Duration.between(startedAt, call.receivedAt()).toMillis());
            var tasks = evidence.putArray("tasks");
            var ids = new ArrayList<>(slowIds); ids.addAll(healthyIds);
            for (UUID id : ids) {
                var state = f.task(id);
                tasks.addObject().put("taskId", id.toString()).put("providerId", state.path("provider_id").asText())
                        .put("status", state.path("status").asText()).put("runAttemptCount", state.path("run_attempt_count").asInt())
                        .put("lastOutcome", state.path("last_outcome").asText());
            }
            Files.createDirectories(Path.of("target/evidence"));
            Files.writeString(Path.of("target/evidence/cp07-supplier-isolation.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
            assertEquals(10, healthy.journal().size());
            for (var call : healthy.journal()) assertTrue(Duration.between(startedAt, call.receivedAt()).compareTo(Duration.ofSeconds(5)) <= 0,
                    "Every healthy task must start within five seconds of releasing the fixed backlog");
            assertTrue(allHealthySucceededMillis >= 0 && allHealthySucceededMillis <= 10_000, "All ten healthy tasks must succeed within ten seconds");
            assertEquals(3, slowPeak, "The fixture must actually occupy the slow provider's full quota");
            assertTrue(healthyPeak > 0); assertEquals(40, f.api.taskCount());
            for (UUID id : healthyIds) { assertEquals("succeeded", f.task(id).path("status").asText()); assertEquals(1, f.attempts(id).size()); }
            releaseSlow.countDown();
        } finally { releaseSlow.countDown(); }
    }
}
