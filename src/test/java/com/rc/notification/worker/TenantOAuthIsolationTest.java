package com.rc.notification.worker;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.auth.TokenCache;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import com.rc.notification.support.WorkerFixture;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** TC-AUTH-04: real persisted tenant contexts, concurrent OAuthStrategy preparation and actual Bearer delivery. */
@Timeout(45)
class TenantOAuthIsolationTest {
    @Test
    void concurrentTenantsSharingOneSupplierNeverShareCredentialsOrCachedBearerTokens() throws Exception {
        assertEquals("cp06 fake:+密钥", System.getenv("CP06_CLIENT_SECRET"));
        assertEquals("fixture-oauth-v1", System.getenv("CP06_CLIENT_SECRET_VERSION"));
        assertEquals("cp06 rotated secret", System.getenv("CP06_ROTATED_SECRET"));
        assertEquals("fixture-oauth-v2", System.getenv("CP06_ROTATED_SECRET_VERSION"));
        var entered = new CountDownLatch(2); var releaseA = new CountDownLatch(1); var releaseB = new CountDownLatch(1);
        try (var supplier = new SharedSupplier(entered, releaseA, releaseB);
             var f = new WorkerFixture(Map.of("app.worker.lease-duration", "12s", "app.worker.attempt-timeout", "8s",
                "app.oauth.token-timeout", "5s", "app.secrets.tenant-references[tenant-a]", "env:CP04_TEST_SECRET,env:CP06_CLIENT_SECRET",
                "app.secrets.tenant-references[tenant-b]", "env:CP06_ROTATED_SECRET"), List.of(supplier.uri("/").getPort()))) {
            JsonNode authA = install(f, supplier, "tenant-a", "a", "CP06_CLIENT_SECRET", "scope.a", 1);
            JsonNode authB = install(f, supplier, "tenant-b", "b", "CP06_ROTATED_SECRET", "scope.b", 2);
            UUID firstA = submit(f, "tenant-a", "a-first"); UUID firstB = submit(f, "tenant-b", "b-first");
            assertEquals(2, f.worker.tick()); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, f.api.application.getBean(TokenCache.class).inFlightCount());
            assertEquals(2, supplier.count("/token")); assertEquals(0, supplier.count("/business"));
            releaseA.countDown(); f.awaitStatus(firstA, "succeeded", Duration.ofSeconds(3));
            assertEquals("processing", f.task(firstB).path("status").asText());
            assertEquals(1, supplier.count("/business"));
            assertEquals("a-first", supplier.businessCalls().get(0).firstHeader("X-Event-Id"));
            assertEquals("Bearer tenant-a-only-token", supplier.businessCalls().get(0).firstHeader("Authorization"));
            releaseB.countDown(); f.awaitStatus(firstB, "succeeded", Duration.ofSeconds(3)); f.awaitIdle(Duration.ofSeconds(3));

            UUID secondA = submit(f, "tenant-a", "a-cache-hit"); UUID secondB = submit(f, "tenant-b", "b-cache-hit");
            assertEquals(2, f.worker.tick());
            for (UUID id : List.of(secondA, secondB)) f.awaitStatus(id, "succeeded", Duration.ofSeconds(3));
            f.awaitIdle(Duration.ofSeconds(3));
            assertEquals(2, supplier.count("/token"), "Each tenant should reuse only its own cached credential context");
            assertEquals(4, supplier.count("/business"));
            for (String event : List.of("a-first", "a-cache-hit", "b-first", "b-cache-hit"))
                assertEquals(1, supplier.businessCalls().stream().filter(call -> event.equals(call.firstHeader("X-Event-Id"))).count());
            for (var request : supplier.businessCalls()) {
                String event = request.firstHeader("X-Event-Id");
                assertTrue(List.of("a-first", "a-cache-hit", "b-first", "b-cache-hit").contains(event));
                assertEquals(event.startsWith("a-") ? "Bearer tenant-a-only-token" : "Bearer tenant-b-only-token", request.firstHeader("Authorization"));
                assertEquals(event, JSON.readTree(request.body()).path("event").asText());
            }
            for (var request : supplier.journal().stream().filter(call -> call.uri().getPath().equals("/token")).toList()) {
                var fields = form(request.body()); boolean tenantA = fields.get("client_id").equals("client-a");
                assertEquals(tenantA ? "client-a" : "client-b", fields.get("client_id"));
                assertEquals(tenantA ? "cp06 fake:+密钥" : "cp06 rotated secret", fields.get("client_secret"));
                assertEquals(tenantA ? "scope.a" : "scope.b", fields.get("scope"));
                assertEquals("client_credentials", fields.get("grant_type"));
            }
            for (UUID id : List.of(firstA, secondA, firstB, secondB)) {
                boolean tenantA = id.equals(firstA) || id.equals(secondA); var state = f.task(id); var attempts = f.attempts(id);
                assertEquals(tenantA ? "tenant-a" : "tenant-b", state.path("tenant_id").asText());
                assertEquals((tenantA ? authA : authB).path("id").asText(), state.path("auth_profile_id").asText());
                assertEquals(1, attempts.size()); assertEquals(1, state.path("total_attempt_count").asInt());
                assertEquals(tenantA ? "fixture-oauth-v1" : "fixture-oauth-v2", attempts.get(0).path("credential_version").asText());
                writeEvidence(f, supplier, id);
            }
        } finally { releaseA.countDown(); releaseB.countDown(); }
    }

    private static JsonNode install(WorkerFixture f, SharedSupplier supplier, String tenant, String clientSuffix, String secretName, String scope, int version) throws Exception {
        var definition = template(tenant, "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\"}}"))
                .put("providerId", "shared-oauth-supplier").put("url", supplier.uri("/business").toString());
        definition.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\"}}"));
        var auth = oauth(tenant, supplier.uri("/token").toString()).put("clientId", "client-" + clientSuffix)
                .put("clientSecretRef", "env:" + secretName).put("clientAuthenticationMethod", "client_secret_post").put("version", version);
        auth.putArray("scopes").add(scope);
        return f.configuration.install(definition, auth, "oauth.notify").auth();
    }
    private static UUID submit(WorkerFixture f, String tenant, String event) throws Exception {
        var response = f.api.post(payload(tenant, "oauth.notify", event, "{}"), ADMIN);
        assertEquals(202, response.statusCode(), response.body()); return UUID.fromString(f.api.json(response).path("notificationId").asText());
    }
    private static Map<String, String> form(byte[] bytes) {
        var result = new LinkedHashMap<String, String>();
        for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    /** A single real server can route by the actual form Body; the production Token URL is identical for both tenants. */
    private static final class SharedSupplier implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        private final List<HttpMock.ReceivedRequest> journal = new java.util.concurrent.CopyOnWriteArrayList<>();
        SharedSupplier(CountDownLatch entered, CountDownLatch releaseA, CountDownLatch releaseB) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(executor);
            server.createContext("/token", exchange -> {
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { HttpMock.respond(exchange, 413, "too large"); return; }
                journal.add(new HttpMock.ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(exchange.getRequestHeaders()), body, Instant.now()));
                String client = form(body).get("client_id");
                if (!List.of("client-a", "client-b").contains(client)) {
                    HttpMock.respond(exchange, 400, "{\"error\":\"invalid_client\"}"); return;
                }
                entered.countDown();
                try {
                    if (!(client.equals("client-a") ? releaseA : releaseB).await(6, TimeUnit.SECONDS)) {
                        HttpMock.respond(exchange, 503, "timeout"); return;
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
                HttpMock.respond(exchange, 200, OAuthFixture.response(client.equals("client-a") ? "tenant-a-only-token" : "tenant-b-only-token", 3600));
            });
            server.createContext("/business", exchange -> {
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                journal.add(new HttpMock.ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(exchange.getRequestHeaders()), body, Instant.now()));
                HttpMock.respond(exchange, 200, "ok");
            });
            server.start();
        }
        java.net.URI uri(String path) { return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
        List<HttpMock.ReceivedRequest> journal() { return List.copyOf(journal); }
        long count(String path) { return journal.stream().filter(call -> call.uri().getPath().equals(path)).count(); }
        List<HttpMock.ReceivedRequest> businessCalls() { return journal.stream().filter(call -> call.uri().getPath().equals("/business")).toList(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    /** No Token request Body, Token fingerprint or authorization header is persisted. */
    private static void writeEvidence(WorkerFixture f, SharedSupplier supplier, UUID id) throws Exception {
        var document = JSON.createObjectNode().put("scenario", "cp09-tenant-oauth-isolation").put("observedAt", Instant.now().toString());
        var source = f.task(id); var task = document.putObject("task");
        for (String key : List.of("id", "tenant_id", "auth_profile_id", "status", "run_number", "run_attempt_count", "total_attempt_count")) task.set(key, source.path(key));
        var attempts = document.putArray("attempts");
        for (var sourceAttempt : f.attempts(id)) {
            var item = attempts.addObject();
            for (String key : List.of("run_number", "attempt_no", "phase", "outcome", "credential_version", "started_at", "ended_at")) item.set(key, sourceAttempt.path(key));
        }
        var tokenCalls = document.putArray("tokenCalls");
        for (var call : supplier.journal().stream().filter(request -> request.uri().getPath().equals("/token")).toList())
            tokenCalls.addObject().put("receivedAt", call.receivedAt().toString()).put("method", call.method()).put("path", call.uri().getPath());
        var calls = document.putArray("httpCalls");
        for (var call : supplier.businessCalls()) calls.addObject().put("receivedAt", call.receivedAt().toString()).put("method", call.method())
                .put("path", call.uri().getPath()).put("bodySha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(call.body())));
        var directory = Path.of("target", "evidence"); Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("cp09-tenant-oauth-isolation-" + id + ".json").toFile(), document);
    }
}
