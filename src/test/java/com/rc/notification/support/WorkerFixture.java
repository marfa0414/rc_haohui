package com.rc.notification.support;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.storage.DeliveryRepository;
import com.rc.notification.worker.WorkerRuntime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** CP04 actual worker, actual delivery transport and actual PostgreSQL; only the supplier is a local server. */
public final class WorkerFixture implements AutoCloseable {
    public final HttpMock mock;
    public NotificationApiFixture api;
    public ConfigurationFixture configuration;
    public DeliveryRepository repository;
    public WorkerRuntime worker;
    private final Map<String, Object> properties = new HashMap<>();
    private final Class<?>[] testSources;

    public WorkerFixture(Class<?>... testSources) throws Exception {
        this(Map.of(), testSources);
    }

    public WorkerFixture(Map<String, Object> overrides, Class<?>... testSources) throws Exception {
        this.testSources = testSources;
        assertEquals("cp04-test-only-secret", System.getenv("CP04_TEST_SECRET"),
                "Set the documented fake CP04_TEST_SECRET to run real environment-backed credential tests; never skip.");
        assertEquals("fixture-v1", System.getenv("CP04_TEST_SECRET_VERSION"));
        mock = new HttpMock();
        properties.put("app.environment", "test");
        properties.put("app.outbound.allowed-hosts", "supplier.invalid,127.0.0.1");
        properties.put("app.outbound.allowed-ports", "443," + mock.uri("/notify").getPort());
        properties.put("app.outbound.development-http-hosts", "127.0.0.1");
        properties.put("app.outbound.development-http-ports", Integer.toString(mock.uri("/notify").getPort()));
        properties.put("app.worker.scheduling-enabled", "false");
        properties.put("app.worker.global-concurrency", "2");
        properties.put("app.worker.lease-duration", "3s");
        properties.put("app.worker.attempt-timeout", "2s");
        properties.put("app.worker.business-timeout", "1s");
        properties.putAll(overrides);
        api = new NotificationApiFixture(new RealPostgres(), "all", properties, testSources);
        configuration = new ConfigurationFixture(api);
        repository = api.application.getBean(DeliveryRepository.class);
        worker = api.application.getBean(WorkerRuntime.class);
        var template = template("tenant-a", "json", JSON.readTree("""
                {"event":{"from":"eventId","type":"string","required":true},
                 "payload":{"from":"data.payload","type":"object"},"marker":{"constant":"cp04"}}
                """)).put("url", mock.uri("/notify").toString()).put("providerId", "cp04-provider");
        template.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\",\"required\":true}}"));
        var auth = apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET").put("prefix", "Key ");
        configuration.install(template, auth, "worker.notify");
    }

    public UUID submit(String event, String data) throws Exception {
        var response = api.post(payload("tenant-a", "worker.notify", event, data), ADMIN);
        assertEquals(202, response.statusCode(), response.body());
        return UUID.fromString(api.json(response).path("notificationId").asText());
    }

    public JsonNode task(UUID id) throws Exception {
        return row("notification_task", "id='" + id + "'");
    }

    public JsonNode row(String table, String predicate) throws Exception {
        try (var connection = api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_jsonb(record)::text FROM " + api.database.schema() + "." + table
                        + " record WHERE " + predicate)) {
            assertTrue(rows.next(), "No " + table + " row matched " + predicate);
            return JSON.readTree(rows.getString(1));
        }
    }

    public List<JsonNode> attempts(UUID taskId) throws Exception {
        try (var connection = api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_jsonb(record)::text FROM " + api.database.schema()
                        + ".notification_attempt record WHERE task_id='" + taskId + "' ORDER BY run_number,attempt_no")) {
            List<JsonNode> records = new ArrayList<>();
            while (rows.next()) records.add(JSON.readTree(rows.getString(1)));
            return records;
        }
    }

    public JsonNode awaitStatus(UUID id, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode latest;
        do {
            latest = task(id);
            if (latest.path("status").asText().equals(expected)) return latest;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        } while (System.nanoTime() < deadline);
        fail("Task did not reach " + expected + ": " + latest);
        return latest;
    }

    public void awaitExpiredLease(UUID id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            try (var connection = api.database.connection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT lease_until <= clock_timestamp() FROM " + api.database.schema()
                            + ".notification_task WHERE id='" + id + "'")) {
                assertTrue(rows.next());
                if (rows.getBoolean(1)) return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        } while (System.nanoTime() < deadline);
        fail("Lease did not expire according to PostgreSQL time");
    }

    public void awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!worker.isIdle() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        assertTrue(worker.isIdle(), "Worker must finish or cancel all owned execution within the bound");
        assertEquals(0, worker.activeCount());
    }

    public void restart(Map<String, Object> overrides) throws Exception {
        var database = api.database;
        api.application.close();
        properties.putAll(overrides);
        api = new NotificationApiFixture(database, "all", properties, false, testSources);
        configuration = new ConfigurationFixture(api);
        repository = api.application.getBean(DeliveryRepository.class);
        worker = api.application.getBean(WorkerRuntime.class);
    }

    /** Explicit allowlist: no request/response bytes, credential headers, tokens or secret references. */
    public void evidence(String scenario, UUID id, Integer sideEffects) throws Exception {
        var document = JSON.createObjectNode().put("scenario", scenario).put("observedAt", java.time.Instant.now().toString());
        var state = document.putObject("task");
        var task = task(id);
        for (String field : List.of("id", "run_number", "run_attempt_count", "total_attempt_count", "status", "lease_token",
                "lease_until", "next_attempt_at", "deadline_at", "last_outcome", "last_error_code", "last_http_status", "defer_reason")) {
            state.set(field, task.path(field));
        }
        var history = document.putArray("attempts");
        for (var attempt : attempts(id)) {
            var entry = history.addObject();
            for (String field : List.of("task_id", "run_number", "attempt_no", "lease_token", "phase", "outcome", "http_status",
                    "error_code", "started_at", "ended_at", "request_started_at", "http_send_started_at", "execution_deadline")) {
                entry.set(field, attempt.path(field));
            }
        }
        var calls = document.putArray("httpCalls");
        for (var request : mock.journal()) {
            calls.addObject().put("receivedAt", request.receivedAt().toString()).put("method", request.method())
                    .put("path", request.uri().getPath()).put("bodySha256", java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(request.body())));
        }
        if (sideEffects != null) document.put("sideEffects", sideEffects);
        var directory = java.nio.file.Path.of("target", "evidence");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(scenario + "-" + id + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }

    @Override public void close() throws Exception {
        try { api.close(); } finally { mock.close(); }
    }
}
