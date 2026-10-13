package com.rc.notification.support;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Independent JVM API/worker scenario. Configuration and task acceptance always use real HTTP. */
public final class ProcessScenario implements AutoCloseable {
    public final RealPostgres database;
    public final HttpMock mock;
    public ProcessHarness api;
    public final Map<String, Object> properties = new HashMap<>();
    public final Map<String, String> environment = new HashMap<>();
    private final List<ProcessHarness> processes = new ArrayList<>();

    public ProcessScenario() throws Exception { this(Map.of(), Map.of()); }
    public ProcessScenario(Map<String, Object> overrides) throws Exception { this(overrides, Map.of()); }
    public ProcessScenario(Map<String, Object> overrides, Map<String, String> env) throws Exception {
        database = new RealPostgres(); mock = new HttpMock();
        properties.put("app.environment", "test");
        properties.put("app.outbound.allowed-hosts", "127.0.0.1,supplier.invalid");
        properties.put("app.outbound.allowed-ports", "443," + mock.uri("/").getPort());
        properties.put("app.outbound.development-http-hosts", "127.0.0.1");
        properties.put("app.outbound.development-http-ports", Integer.toString(mock.uri("/").getPort()));
        properties.put("app.outbound.development-allowed-cidrs", "127.0.0.1/32");
        properties.put("app.worker.scheduling-enabled", "false");
        properties.put("app.worker.global-concurrency", "2"); properties.put("app.worker.provider-concurrency", "2");
        properties.put("app.worker.lease-duration", "8s"); properties.put("app.worker.attempt-timeout", "4s");
        properties.put("app.worker.business-timeout", "2s"); properties.put("app.worker.shutdown-grace-period", "1s");
        properties.put("app.oauth.token-timeout", "1s");
        properties.put("app.retry.base-delay", "10ms"); properties.put("app.retry.max-delay", "20ms");
        properties.put("app.delivery.run-duration", "2m");
        properties.put("app.secrets.tenant-references[tenant-a]", "env:CP04_TEST_SECRET,env:CP09_ROTATING_SECRET");
        properties.putAll(overrides);
        if (overrides.containsKey("app.outbound.development-http-ports")) {
            properties.put("app.outbound.development-http-ports", mock.uri("/").getPort() + "," + overrides.get("app.outbound.development-http-ports"));
        }
        environment.put("CP04_TEST_SECRET", "cp04-test-only-secret"); environment.put("CP04_TEST_SECRET_VERSION", "fixture-v1");
        environment.put("CP09_ROTATING_SECRET", "cp09-original-fake-secret"); environment.put("CP09_ROTATING_SECRET_VERSION", "cp09-version-1");
        environment.putAll(env);
        try {
            api = start("api", Map.of(), Map.of());
            var template = template("tenant-a", "json", JSON.readTree("{\"event\":{\"from\":\"eventId\",\"type\":\"string\"},\"payload\":{\"from\":\"data\",\"type\":\"object\"}}"))
                    .put("url", mock.uri("/notify").toString()).put("providerId", "cp09-provider");
            template.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\",\"required\":true}}"));
            install(template, apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET"), "worker.notify");
        } catch (Throwable failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public ProcessHarness startWorker(Map<String, Object> overrides) throws Exception { return start("worker", overrides, Map.of()); }
    public ProcessHarness startWorker(Map<String, Object> overrides, Map<String, String> env) throws Exception { return start("worker", overrides, env); }
    public ProcessHarness start(String mode, Map<String, Object> overrides, Map<String, String> env) throws Exception {
        var actual = new HashMap<String, Object>(properties); actual.putAll(overrides);
        var actualEnvironment = new HashMap<String, String>(environment); actualEnvironment.putAll(env);
        ProcessHarness process = ProcessHarness.start(database, mode, actual, actualEnvironment); processes.add(process); return process;
    }
    public UUID submit(String event, String data) throws Exception { return submit("worker.notify", event, data); }
    public UUID submit(String operation, String event, String data) throws Exception {
        var response = request(api, "POST", "/v1/notifications", JSON.readTree(payload("tenant-a", operation, event, data)), ADMIN);
        assertEquals(202, response.statusCode(), response.body()); return UUID.fromString(JSON.readTree(response.body()).path("notificationId").asText());
    }
    public HttpResponse<String> send(String method, String path, JsonNode body, String token) throws Exception { return request(api, method, path, body, token); }
    public HttpResponse<String> get(String path, String token) throws Exception { return request(api, "GET", path, null, token); }
    public static HttpResponse<String> request(ProcessHarness process, String method, String path, JsonNode body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(process.baseUri().resolve(path)).timeout(Duration.ofSeconds(12));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
    public JsonNode createTemplate(JsonNode value) throws Exception { return create("template-versions", value); }
    public JsonNode createAuth(JsonNode value) throws Exception { return create("auth-profiles", value); }
    private JsonNode create(String resource, JsonNode value) throws Exception {
        var response = send("POST", "/admin/" + resource, value, ADMIN); assertEquals(201, response.statusCode(), response.body()); return JSON.readTree(response.body());
    }
    public JsonNode publish(String resource, JsonNode version) throws Exception {
        var response = send("POST", "/admin/" + resource + "/" + version.path("id").asText() + "/publish",
                JSON.createObjectNode().put("expectedRevision", version.path("revision").asLong()), ADMIN);
        assertEquals(200, response.statusCode(), response.body()); return JSON.readTree(response.body());
    }
    public JsonNode bind(String tenant, String operation, JsonNode template, JsonNode auth, long revision) throws Exception {
        var response = send("PUT", "/admin/routes/" + tenant + "/" + operation, routeBody(template, auth, revision), ADMIN);
        assertEquals(200, response.statusCode(), response.body()); return JSON.readTree(response.body());
    }
    public ConfigurationFixture.Published install(ObjectNode template, ObjectNode auth, String operation) throws Exception {
        var publishedTemplate = publish("template-versions", createTemplate(template)); var publishedAuth = publish("auth-profiles", createAuth(auth));
        return new ConfigurationFixture.Published(publishedTemplate, publishedAuth, bind(auth.path("tenantId").asText(), operation, publishedTemplate, publishedAuth, 0));
    }
    public void sql(String sql) throws Exception {
        try (var connection = database.connection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + database.schema()); statement.execute(sql);
        }
    }
    public JsonNode task(UUID id) throws Exception {
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT to_jsonb(t)::text FROM " + database.schema() + ".notification_task t WHERE id=?")) {
            statement.setObject(1, id); try (var rows = statement.executeQuery()) { assertTrue(rows.next(), "Accepted task is missing"); return JSON.readTree(rows.getString(1)); }
        }
    }
    public List<JsonNode> attempts(UUID id) throws Exception {
        try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT to_jsonb(a)::text FROM " + database.schema() + ".notification_attempt a WHERE task_id=? ORDER BY run_number,attempt_no")) {
            statement.setObject(1, id); try (var rows = statement.executeQuery()) { var result = new ArrayList<JsonNode>(); while (rows.next()) result.add(JSON.readTree(rows.getString(1))); return result; }
        }
    }
    public long taskCount() throws Exception {
        try (var connection = database.connection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + database.schema() + ".notification_task")) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    public JsonNode awaitStatus(UUID id, String status, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos(); JsonNode latest;
        do { latest = task(id); if (latest.path("status").asText().equals(status)) return latest; Thread.sleep(10); } while (System.nanoTime() < end);
        throw new AssertionError("Task " + id + " did not reach " + status + "; actual status=" + latest.path("status").asText());
    }
    public void awaitExpiredLease(UUID id, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        do {
            try (var connection = database.connection(); var statement = connection.prepareStatement("SELECT lease_until <= clock_timestamp() FROM " + database.schema() + ".notification_task WHERE id=?")) {
                statement.setObject(1, id); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); if (rows.getBoolean(1)) return; }
            }
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        throw new AssertionError("Task lease did not expire according to database time");
    }
    public JsonNode tickUntil(ProcessHarness worker, UUID id, String status, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        do { worker.tick(); worker.awaitIdle(Duration.ofSeconds(5)); var value = task(id); if (value.path("status").asText().equals(status)) return value; Thread.sleep(20); } while (System.nanoTime() < end);
        return awaitStatus(id, status, Duration.ofMillis(1));
    }
    /** Evidence is allowlisted; credentials, token digests, accepted Body and raw response headers never enter it. */
    public void evidence(String scenario, UUID id, Integer sideEffects) throws Exception {
        if (!scenario.matches("cp09-[a-z0-9-]+")) throw new IllegalArgumentException("Invalid scenario name");
        var document = JSON.createObjectNode().put("scenario", scenario).put("observedAt", Instant.now().toString());
        var state = document.putObject("task"); var task = task(id);
        for (String key : List.of("id", "status", "run_number", "run_attempt_count", "total_attempt_count", "lease_token", "lease_until", "next_attempt_at", "deadline_at", "last_outcome", "last_error_code", "last_http_status", "defer_reason", "auth_recovery_used", "auth_recovery_required", "auth_profile_id")) state.set(key, task.path(key));
        var history = document.putArray("attempts");
        for (var attempt : attempts(id)) {
            var item = history.addObject();
            for (String key : List.of("run_number", "attempt_no", "lease_token", "phase", "outcome", "http_status", "error_code", "credential_version", "started_at", "request_started_at", "http_send_started_at", "ended_at")) item.set(key, attempt.path(key));
        }
        var childStates = document.putArray("processes");
        for (var process : processes) {
            var child = childStates.addObject().put("pid", process.pid()).put("alive", process.isAlive());
            child.set("phases", JSON.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(process.phaseEvents()));
            child.set("lifecycle", JSON.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).valueToTree(process.lifecycle()));
        }
        var journal = document.putArray("httpCalls");
        for (var request : mock.journal()) journal.addObject().put("receivedAt", request.receivedAt().toString()).put("method", request.method())
                .put("path", request.uri().getPath()).put("bodySha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.body())));
        if (sideEffects != null) document.put("sideEffects", sideEffects);
        Path directory = Path.of("target", "evidence"); Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario + "-" + id + ".json").toFile(), document);
    }
    @Override public void close() throws Exception {
        Exception failure = null;
        for (int index = processes.size() - 1; index >= 0; index--) {
            try { processes.get(index).close(); } catch (Exception exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
        }
        try { mock.close(); } catch (Exception exception) { if (failure == null) failure = exception; }
        try { database.close(); } catch (Exception exception) { if (failure == null) failure = exception; }
        if (failure != null) throw failure;
    }
}
