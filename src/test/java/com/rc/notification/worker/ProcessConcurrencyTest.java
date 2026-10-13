package com.rc.notification.worker;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.worker.ProcessWorkerRecoveryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.ConfigurationFixture.Published;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.ProcessHarness;
import com.rc.notification.support.ProcessScenario;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP09 TC-IDEM-01/07, TC-CFG-05/06, TC-WORK-01 and TC-OPS-01: independent processes sharing PostgreSQL. */
@Timeout(180)
class ProcessConcurrencyTest {
    @Test
    void twoApiProcessesDeduplicateFiftyRequestsAndTwoNativeSchedulersDeliverWithoutHoldingRowLocks() throws Exception {
        var fourArrivals = new CountDownLatch(4); var release = new CountDownLatch(1);
        var effects = ConcurrentHashMap.<String>newKeySet();
        try (var f = new ProcessScenario(Map.of("app.worker.lease-duration", "18s", "app.worker.attempt-timeout", "12s",
                "app.worker.business-timeout", "10s"))) {
            f.mock.route("/notify", exchange -> {
                fourArrivals.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Four real HTTP slots were never released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
                effects.add(exchange.getRequestHeaders().getFirst("X-Event-Id")); HttpMock.respond(exchange, 200, "ok");
            });
            var secondApi = f.start("api", Map.of(), Map.of());
            var first = f.startWorker(Map.of("app.worker.scheduling-enabled", "true", "app.worker.poll-interval", "30ms"));
            var second = f.startWorker(Map.of("app.worker.scheduling-enabled", "true", "app.worker.poll-interval", "30ms"));
            assertEquals(4, Set.of(f.api.pid(), secondApi.pid(), first.pid(), second.pid()).size());
            assertFalse(f.api.state().path("workerEnabled").asBoolean()); assertTrue(f.api.state().path("apiEnabled").asBoolean());
            for (var worker : List.of(first, second)) {
                assertTrue(worker.state().path("workerEnabled").asBoolean()); assertFalse(worker.state().path("apiEnabled").asBoolean());
                assertEquals(404, ProcessScenario.request(worker, "GET", "/v1/notifications", null, ADMIN).statusCode());
            }
            var start = new CountDownLatch(1); var ready = new CountDownLatch(50);
            var executor = Executors.newFixedThreadPool(50); var requests = new ArrayList<Future<HttpResponse<String>>>();
            try {
                for (int index = 0; index < 50; index++) {
                    ProcessHarness api = index % 2 == 0 ? f.api : secondApi;
                    requests.add(executor.submit(() -> {
                        ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                        return ProcessScenario.request(api, "POST", "/v1/notifications",
                                JSON.readTree(payload("tenant-a", "worker.notify", "fifty-one-event", "{\"n\":1}")), ADMIN);
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                int created = 0; UUID shared = null;
                for (var future : requests) {
                    var response = future.get(15, TimeUnit.SECONDS);
                    assertTrue(response.statusCode() == 200 || response.statusCode() == 202, response.body());
                    var result = JSON.readTree(response.body()); UUID id = UUID.fromString(result.path("notificationId").asText());
                    if (shared == null) shared = id; else assertEquals(shared, id);
                    if (response.statusCode() == 202) { created++; assertFalse(result.path("deduplicated").asBoolean()); }
                    else assertTrue(result.path("deduplicated").asBoolean());
                }
                assertEquals(1, created); assertEquals(1, f.taskCount());
                var accepted = new ArrayList<UUID>(); accepted.add(shared);
                for (int index = 0; index < 11; index++) accepted.add(f.submit("normal-" + index, "{\"n\":1}"));
                assertTrue(fourArrivals.await(5, TimeUnit.SECONDS), "Both workers must occupy their real HTTP capacity");
                assertEquals(2, first.state().path("active").asInt()); assertEquals(2, second.state().path("active").asInt());
                assertEquals(0, first.state().path("queued").asInt()); assertEquals(0, second.state().path("queued").asInt());
                var activeIds = new ArrayList<UUID>();
                for (UUID id : accepted) if (f.task(id).path("status").asText().equals("processing")) activeIds.add(id);
                assertEquals(4, activeIds.size());
                try (var connection = f.database.connection()) {
                    connection.setAutoCommit(false);
                    try (var query = connection.prepareStatement("SELECT id FROM " + f.database.schema() + ".notification_task WHERE id=? FOR UPDATE NOWAIT")) {
                        for (UUID id : activeIds) { query.setObject(1, id); try (var rows = query.executeQuery()) { assertTrue(rows.next()); } }
                    } finally { connection.rollback(); }
                }
                release.countDown();
                for (UUID id : accepted) {
                    f.awaitStatus(id, "succeeded", WAIT); assertEquals(1, f.attempts(id).size());
                    assertEquals(1, f.task(id).path("total_attempt_count").asInt());
                    String event = f.task(id).path("event_id").asText();
                    var calls = f.mock.journal().stream().filter(call -> event.equals(call.firstHeader("X-Event-Id"))).toList();
                    assertEquals(1, calls.size()); assertArrayEquals(snapshotBody(f.task(id)), calls.get(0).body());
                }
                first.awaitIdle(WAIT); second.awaitIdle(WAIT);
                var claims = new ArrayList<ProcessHarness.PhaseEvent>(); claims.addAll(first.phaseEvents()); claims.addAll(second.phaseEvents());
                for (UUID id : accepted) assertEquals(1, claims.stream().filter(event -> event.taskId().equals(id) && event.phase() == Phase.AFTER_CLAIM).count());
                assertEquals(12, effects.size()); assertEquals(12, f.mock.journal().size());
                for (UUID id : accepted) f.evidence("cp09-dual-normal", id, effects.size());
                reconcile(f, "cp09-dual-normal", accepted, List.of(f.api, secondApi, first, second), effects.size());
            } finally { release.countDown(); start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        } finally { release.countDown(); }
    }

    @Test
    void concurrentPublicationAndProcessRestartNeverMixSnapshotsOrReplaceAnAcceptedEvent() throws Exception {
        var arrivals = new ConcurrentHashMap<String, AtomicInteger>();
        try (var f = new ProcessScenario(Map.of("app.worker.lease-duration", "40s", "app.worker.attempt-timeout", "35s"))) {
            for (String path : List.of("/version-one", "/version-two")) f.mock.route(path, exchange -> {
                String event = exchange.getRequestHeaders().getFirst("X-Event-Id");
                int count = arrivals.computeIfAbsent(event, ignored -> new AtomicInteger()).incrementAndGet();
                HttpMock.respond(exchange, "old-in-flight".equals(event) && count == 1 ? 503 : 200, "versioned");
            });
            Published one = f.install(versionedTemplate(f, "v1", "/version-one"), apiKey("tenant-a")
                    .put("secretRef", "env:CP04_TEST_SECRET").put("prefix", "V1 "), "versioned.notify");
            JsonNode twoTemplate = f.publish("template-versions", f.createTemplate(versionedTemplate(f, "v2", "/version-two")));
            JsonNode twoAuth = f.publish("auth-profiles", f.createAuth(apiKey("tenant-a")
                    .put("secretRef", "env:CP09_ROTATING_SECRET").put("prefix", "V2 ")));
            var secondApi = f.start("api", Map.of(), Map.of());
            var first = f.startWorker(Map.of()); var second = f.startWorker(Map.of());
            var accepted = new ArrayList<UUID>(); UUID old = f.submit("versioned.notify", "old-in-flight", "{\"value\":1}"); accepted.add(old);
            JsonNode original = f.task(old);
            first.arm(Phase.AFTER_HTTP, old); first.tick(); first.awaitPhase(Phase.AFTER_HTTP, old, WAIT);
            assertEquals(1, f.mock.journal().size());
            long initialRevision = f.bind("tenant-a", "versioned.notify", twoTemplate, twoAuth, one.route().path("revision").asLong()).path("revision").asLong();
            UUID definitelyTwo = f.submit("versioned.notify", "definitely-v2", "{}"); accepted.add(definitelyTwo);
            var revision = new AtomicLong(initialRevision); var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
            try {
                var changing = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int iteration = 0; iteration < 12; iteration++) {
                        boolean useOne = iteration % 2 == 0;
                        var route = f.bind("tenant-a", "versioned.notify", useOne ? one.template() : twoTemplate,
                                useOne ? one.auth() : twoAuth, revision.get()); revision.set(route.path("revision").asLong());
                    }
                    return true;
                });
                var accepting = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS)); var result = new ArrayList<UUID>();
                    for (int index = 0; index < 12; index++) {
                        var response = ProcessScenario.request(secondApi, "POST", "/v1/notifications",
                                JSON.readTree(payload("tenant-a", "versioned.notify", "publication-race-" + index, "{}")), ADMIN);
                        assertEquals(202, response.statusCode(), response.body()); result.add(UUID.fromString(JSON.readTree(response.body()).path("notificationId").asText()));
                    }
                    return result;
                });
                start.countDown(); assertTrue(changing.get(20, TimeUnit.SECONDS)); accepted.addAll(accepting.get(20, TimeUnit.SECONDS));
            } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
            f.bind("tenant-a", "versioned.notify", one.template(), one.auth(), revision.get());
            UUID rolledBack = f.submit("versioned.notify", "after-rollback", "{}"); accepted.add(rolledBack);
            assertEquals(one.template().path("id").asText(), f.task(rolledBack).path("template_version_id").asText());
            assertEquals(twoTemplate.path("id").asText(), f.task(definitelyTwo).path("template_version_id").asText());
            var oldApi = f.api; oldApi.forceKill(); f.api = f.start("api", Map.of(), Map.of());
            var repeated = f.send("POST", "/v1/notifications", JSON.readTree(payload("tenant-a", "versioned.notify", "old-in-flight", "{\"value\":1}")), ADMIN);
            assertEquals(200, repeated.statusCode(), repeated.body()); assertEquals(old.toString(), JSON.readTree(repeated.body()).path("notificationId").asText());
            assertTrue(JSON.readTree(repeated.body()).path("deduplicated").asBoolean());
            for (String field : List.of("request_hash", "template_version_id", "auth_profile_id", "request_url", "ordinary_headers", "body_bytes", "route_revision"))
                assertEquals(original.path(field), f.task(old).path(field), field);
            first.release(); first.awaitIdle(WAIT); first.startPolling(Duration.ofMillis(30)); second.startPolling(Duration.ofMillis(30));
            for (UUID id : accepted) f.awaitStatus(id, "succeeded", WAIT);
            first.stopPolling(); second.stopPolling(); first.awaitIdle(WAIT); second.awaitIdle(WAIT);
            for (UUID id : accepted) {
                var task = f.task(id); boolean usesOne = task.path("template_version_id").asText().equals(one.template().path("id").asText());
                assertTrue(usesOne || task.path("template_version_id").asText().equals(twoTemplate.path("id").asText()));
                String marker = usesOne ? "v1" : "v2"; String path = usesOne ? "/version-one" : "/version-two";
                assertEquals((usesOne ? one.auth() : twoAuth).path("id").asText(), task.path("auth_profile_id").asText());
                assertEquals(f.mock.uri(path).toString(), task.path("request_url").asText());
                assertEquals(marker, task.path("ordinary_headers").path("X-Version").asText());
                assertEquals(marker, JSON.readTree(snapshotBody(task)).path("version").asText());
                var calls = f.mock.journal().stream().filter(call -> task.path("event_id").asText().equals(call.firstHeader("X-Event-Id"))).toList();
                assertEquals(id.equals(old) ? 2 : 1, calls.size());
                for (var call : calls) {
                    assertEquals(path, call.uri().getPath()); assertEquals(marker, call.firstHeader("X-Version"));
                    assertEquals(usesOne ? "V1 cp04-test-only-secret" : "V2 cp09-original-fake-secret", call.firstHeader("X-Api-Key"));
                    assertArrayEquals(snapshotBody(task), call.body());
                }
                f.evidence("cp09-publication-restart", id, null);
            }
            assertEquals(2, f.attempts(old).size()); assertEquals("HTTP_REJECTED", f.attempts(old).get(0).path("outcome").asText());
            reconcile(f, "cp09-publication-restart", accepted, List.of(oldApi, f.api, secondApi, first, second), null);
        }
    }

    private static ObjectNode versionedTemplate(ProcessScenario f, String marker, String path) throws Exception {
        var fields = JSON.createObjectNode(); fields.set("version", JSON.createObjectNode().put("constant", marker));
        fields.set("event", JSON.createObjectNode().put("from", "eventId").put("type", "string"));
        var result = template("tenant-a", "json", fields).put("url", f.mock.uri(path).toString()).put("providerId", "versioned-provider");
        result.set("headers", JSON.createObjectNode().set("X-Version", JSON.createObjectNode().put("constant", marker)));
        ((ObjectNode) result.path("headers")).set("X-Event-Id", JSON.createObjectNode().put("from", "eventId").put("type", "string"));
        return result;
    }
}
