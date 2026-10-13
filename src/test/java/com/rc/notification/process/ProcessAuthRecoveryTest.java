package com.rc.notification.process;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import com.rc.notification.support.ProcessHarness;
import com.rc.notification.support.ProcessScenario;
import com.rc.notification.worker.WorkerLifecycleListener.Phase;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AUTH-12/13 and GUARD-07 use real new JVMs, real environment-backed secrets and durable state. */
@Timeout(150)
class ProcessAuthRecoveryTest {
    @Test
    void rotatingTheSameSecretReferenceUsesNewJvmEnvironmentButDoesNotRewriteQueuedAuthVersions() throws Exception {
        try (var tokens = new HttpMock(); var f = new ProcessScenario(Map.of("app.outbound.development-http-ports", tokens.uri("/").getPort(), "app.worker.global-concurrency", "1"))) {
            var oldFetches = new AtomicInteger();
            tokens.route("/old-token", e -> HttpMock.respond(e, 200, OAuthFixture.response("old-account-token-" + oldFetches.incrementAndGet(), 3600)));
            tokens.route("/new-token", e -> HttpMock.respond(e, 200, OAuthFixture.response("new-account-token", 3600)));
            f.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var definition = template("tenant-a", "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\"}}"))
                    .put("providerId", "cp09-oauth").put("url", f.mock.uri("/business").toString());
            definition.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\"}}"));
            var firstDefinition = oauth("tenant-a", tokens.uri("/old-token").toString()).put("clientId", "old-client")
                    .put("clientSecretRef", "env:CP09_ROTATING_SECRET").put("clientAuthenticationMethod", "client_secret_post");
            firstDefinition.putArray("scopes").add("old.scope");
            var installed = f.install(definition, firstDefinition, "oauth.notify");
            var oldWorker = f.startWorker(Map.of());
            var warm = f.submit("oauth.notify", "old-jvm-cache-warm", "{}");
            f.tickUntil(oldWorker, warm, "succeeded", Duration.ofSeconds(8));
            assertEquals(1, tokens.count("/old-token"));
            assertEquals("cp09-original-fake-secret", form(tokens.journal().get(0).body()).get("client_secret"));
            var queuedOld = f.submit("oauth.notify", "old-version-already-accepted", "{}");
            var newDefinition = firstDefinition.deepCopy().put("version", 2).put("clientId", "new-client").put("tokenUrl", tokens.uri("/new-token").toString());
            newDefinition.putArray("scopes").add("new.scope");
            var newAuth = f.publish("auth-profiles", f.createAuth(newDefinition));
            f.bind("tenant-a", "oauth.notify", installed.template(), newAuth, installed.route().path("revision").asLong());
            var queuedNew = f.submit("oauth.notify", "new-version-already-accepted", "{}");
            assertEquals(installed.auth().path("id").asText(), f.task(queuedOld).path("auth_profile_id").asText());
            assertEquals(newAuth.path("id").asText(), f.task(queuedNew).path("auth_profile_id").asText());
            long oldPid = oldWorker.pid(); oldWorker.forceKill(); Instant killedAt = Instant.now(); assertFalse(oldWorker.isAlive());
            var newWorker = f.startWorker(Map.of(), Map.of("CP09_ROTATING_SECRET", "cp09-rotated-fake-secret", "CP09_ROTATING_SECRET_VERSION", "cp09-version-2"));
            assertNotEquals(oldPid, newWorker.pid());
            f.tickUntil(newWorker, queuedOld, "succeeded", Duration.ofSeconds(8)); f.tickUntil(newWorker, queuedNew, "succeeded", Duration.ofSeconds(8));
            assertEquals(2, tokens.count("/old-token")); assertEquals(1, tokens.count("/new-token")); assertEquals(3, f.mock.journal().size());
            var oldRequest = tokens.journal().stream().filter(call -> call.uri().getPath().equals("/old-token")).skip(1).findFirst().orElseThrow();
            var newRequest = tokens.journal().stream().filter(call -> call.uri().getPath().equals("/new-token")).findFirst().orElseThrow();
            assertEquals("old-client", form(oldRequest.body()).get("client_id")); assertEquals("old.scope", form(oldRequest.body()).get("scope"));
            assertEquals("new-client", form(newRequest.body()).get("client_id")); assertEquals("new.scope", form(newRequest.body()).get("scope"));
            assertEquals("cp09-rotated-fake-secret", form(oldRequest.body()).get("client_secret"));
            assertEquals("cp09-rotated-fake-secret", form(newRequest.body()).get("client_secret"));
            assertEquals("cp09-version-1", f.attempts(warm).get(0).path("credential_version").asText());
            assertEquals("cp09-version-2", f.attempts(queuedOld).get(0).path("credential_version").asText());
            assertEquals("cp09-version-2", f.attempts(queuedNew).get(0).path("credential_version").asText());
            assertEquals(installed.auth().path("id").asText(), f.task(queuedOld).path("auth_profile_id").asText());
            f.evidence("cp09-auth-secret-rotation-warm", warm, null);
            f.evidence("cp09-auth-secret-rotation-old", queuedOld, null); f.evidence("cp09-auth-secret-rotation-new", queuedNew, null);
            summary("cp09-auth-secret-rotation", Map.of("oldPid", oldPid, "newPid", newWorker.pid(), "killedAt", killedAt.toString(),
                    "tokenRequestsBeforeRestart", 1, "tokenRequestsAfterRestart", 2, "acceptedTasks", 3, "businessRequests", 3,
                    "queuedVersionPreserved", true, "newEnvironmentCredentialUsed", true), List.of(warm, queuedOld, queuedNew));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void committedInvalidTokenRecoverySurvivesKillAndCannotRefreshMoreThanOncePerRun(boolean rejectedAgain) throws Exception {
        try (var tokens = new HttpMock(); var f = new ProcessScenario(Map.of("app.outbound.development-http-ports", tokens.uri("/").getPort(), "app.worker.global-concurrency", "1"))) {
            var tokenCalls = new AtomicInteger(); var businessCalls = new AtomicInteger();
            tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response(tokenCalls.incrementAndGet() == 1 ? "rejected-token" : "fresh-token", 3600)));
            f.mock.route("/business", e -> {
                int call = businessCalls.incrementAndGet();
                if (call == 1 || rejectedAgain) { e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "ignored"); }
                else HttpMock.respond(e, 200, "ok");
            });
            installOAuth(f, tokens, "/token", "recovery.notify");
            var id = f.submit("recovery.notify", "committed-recovery-" + rejectedAgain, "{}");
            var original = f.startWorker(Map.of()); original.arm(Phase.AFTER_COMPLETION, id); assertEquals(1, original.tick());
            var barrier = original.awaitPhase(Phase.AFTER_COMPLETION, id, Duration.ofSeconds(8));
            var committed = f.task(id); assertEquals("pending", committed.path("status").asText());
            assertTrue(committed.path("auth_recovery_used").asBoolean()); assertTrue(committed.path("auth_recovery_required").asBoolean());
            assertEquals(1, committed.path("run_attempt_count").asInt()); assertEquals(1, f.mock.journal().size());
            assertEquals("HTTP_REJECTED", f.attempts(id).get(0).path("outcome").asText());
            assertEquals(401, f.attempts(id).get(0).path("http_status").asInt());
            f.evidence("cp09-auth-intent-committed-" + rejectedAgain, id, null);
            long oldPid = original.pid(); original.forceKill(); Instant killedAt = Instant.now(); assertFalse(original.isAlive());
            var restarted = f.startWorker(Map.of()); assertNotEquals(oldPid, restarted.pid());
            var terminal = f.tickUntil(restarted, id, rejectedAgain ? "failed" : "succeeded", Duration.ofSeconds(8));
            assertEquals(2, terminal.path("run_attempt_count").asInt()); assertEquals(2, terminal.path("total_attempt_count").asInt());
            assertTrue(terminal.path("auth_recovery_used").asBoolean()); assertFalse(terminal.path("auth_recovery_required").asBoolean());
            assertEquals(2, f.attempts(id).size()); assertEquals(2, tokens.journal().size()); assertEquals(2, f.mock.journal().size());
            assertNotEquals(f.mock.journal().get(0).firstHeader("Authorization"), f.mock.journal().get(1).firstHeader("Authorization"));
            assertEquals("Bearer fresh-token", f.mock.journal().get(1).firstHeader("Authorization"));
            for (int n = 0; n < 3; n++) { restarted.tick(); restarted.awaitIdle(Duration.ofSeconds(2)); }
            assertEquals(2, tokens.journal().size()); assertEquals(2, f.mock.journal().size());
            f.evidence("cp09-auth-restarted-" + rejectedAgain, id, null);
            summary("cp09-auth-recovery-" + rejectedAgain, Map.of("oldPid", oldPid, "newPid", restarted.pid(), "killedAt", killedAt.toString(),
                    "committedPhase", barrier.phase().toString(), "rejectedAgain", rejectedAgain, "terminalStatus", terminal.path("status").asText(),
                    "tokenCalls", 2, "businessCalls", 2, "usedDifferentTokenAfterRestart", true), List.of(id));
        }
    }

    @Test
    void restartingAWorkerClearsOnlyLocalCircuitStateAndPreservesPreviouslyDeferredTasks() throws Exception {
        try (var f = new ProcessScenario(Map.of("app.delivery.max-attempts", "1", "app.protection.open-duration", "60s", "app.worker.global-concurrency", "1"))) {
            var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            f.mock.route("/notify", e -> HttpMock.respond(e, fail.get() ? 503 : 200, "ignored"));
            var original = f.startWorker(Map.of());
            var acceptedTaskIds = new ArrayList<UUID>();
            for (int n = 0; n < 10; n++) {
                var failed = f.submit("open-circuit-" + n, "{}"); acceptedTaskIds.add(failed);
                f.tickUntil(original, failed, "failed", Duration.ofSeconds(5));
            }
            assertEquals(10, f.mock.journal().size());
            var deferred = f.submit("deferred-by-original-process", "{}"); original.tick(); original.awaitIdle(Duration.ofSeconds(3));
            var waiting = f.task(deferred); assertEquals("pending", waiting.path("status").asText());
            assertEquals("CIRCUIT_OPEN", waiting.path("defer_reason").asText()); assertEquals(0, waiting.path("run_attempt_count").asInt());
            String next = waiting.path("next_attempt_at").asText(); long oldPid = original.pid(); original.forceKill(); Instant killed = Instant.now();
            fail.set(false); var restarted = f.startWorker(Map.of()); assertNotEquals(oldPid, restarted.pid());
            var healthy = f.submit("new-work-after-circuit-process-exit", "{}");
            f.tickUntil(restarted, healthy, "succeeded", Duration.ofSeconds(5));
            assertEquals(11, f.mock.journal().size()); assertEquals(12, f.taskCount());
            assertEquals("pending", f.task(deferred).path("status").asText()); assertEquals(next, f.task(deferred).path("next_attempt_at").asText());
            assertEquals(0, f.task(deferred).path("run_attempt_count").asInt()); assertTrue(f.attempts(deferred).isEmpty());
            for (var failed : acceptedTaskIds) f.evidence("cp09-local-circuit-failed-before-restart", failed, null);
            f.evidence("cp09-local-circuit-durable-deferral", deferred, null); f.evidence("cp09-local-circuit-reset", healthy, null);
            acceptedTaskIds.add(deferred); acceptedTaskIds.add(healthy);
            summary("cp09-circuit-process-restart", Map.of("oldPid", oldPid, "newPid", restarted.pid(), "killedAt", killed.toString(),
                    "acceptedTasks", 12, "businessRequests", 11, "deferredTasksPreserved", 1, "freshProcessCanSend", true), acceptedTaskIds);
        }
    }

    private static void installOAuth(ProcessScenario f, HttpMock tokens, String path, String operation) throws Exception {
        var definition = template("tenant-a", "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\"}}"))
                .put("providerId", "cp09-oauth").put("url", f.mock.uri("/business").toString());
        definition.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\"}}"));
        f.install(definition, oauth("tenant-a", tokens.uri(path).toString()).put("clientSecretRef", "env:CP09_ROTATING_SECRET"), operation);
    }
    private static Map<String, String> form(byte[] bytes) {
        var fields = new LinkedHashMap<String, String>();
        for (String item : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] parts = item.split("=", 2); fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return fields;
    }
    static void summary(String scenario, Map<String, ?> values, List<UUID> acceptedTaskIds) throws Exception {
        var target = Path.of("target", "evidence"); Files.createDirectories(target);
        var evidence = new LinkedHashMap<String, Object>(values);
        evidence.put("acceptedTaskIds", acceptedTaskIds.stream().map(UUID::toString).toList());
        JSON.writerWithDefaultPrettyPrinter().writeValue(target.resolve(scenario + "-summary.json").toFile(), evidence);
    }
}
