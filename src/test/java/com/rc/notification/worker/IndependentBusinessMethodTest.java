package com.rc.notification.worker;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TC-MAP-08: supported non-POST methods pass through the real published snapshot and transport. */
@Timeout(45)
class IndependentBusinessMethodTest {
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH"})
    void configuredMethodAndImmutableBytesReachTheSupplierExactlyOnce(String method) throws Exception {
        try (var fixture = new WorkerFixture(Map.of("app.oauth.token-timeout", "1s",
                "app.worker.business-timeout", "2s", "app.worker.attempt-timeout", "4s",
                "app.worker.lease-duration", "8s"))) {
            String suffix = method.toLowerCase(Locale.ROOT);
            String operation = "business." + suffix;
            String event = "configured-method-" + suffix;
            String path = "/configured-" + suffix;
            fixture.mock.route(path, exchange -> HttpMock.respond(exchange, 200, "acknowledged"));
            var definition = template("tenant-a", "json", JSON.readTree("""
                    {"event":{"from":"eventId","type":"string","required":true},
                     "payload":{"from":"data","type":"object","required":true}}
                    """)).put("method", method).put("url", fixture.mock.uri(path).toString())
                    .put("providerId", "cp09-method-provider");
            definition.set("headers", JSON.readTree("{\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\",\"required\":true}}"));
            var installed = fixture.configuration.install(definition, apiKey("tenant-a")
                    .put("secretRef", "env:CP04_TEST_SECRET").put("prefix", "Method "), operation);
            assertEquals("published", installed.template().path("status").asText());
            assertEquals(method, installed.template().path("method").asText());
            assertEquals(0, fixture.mock.journal().size(), "Publishing and binding are offline");

            String data = "{\"text\":\"中文\\n\\\"quoted\\\"\",\"amount\":123456789012345678901,\"enabled\":false}";
            var accepted = fixture.api.post(payload("tenant-a", operation, event, data), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            UUID id = UUID.fromString(fixture.api.json(accepted).path("notificationId").asText());
            var pending = fixture.task(id);
            assertEquals("pending", pending.path("status").asText());
            assertEquals(method, pending.path("request_method").asText());
            assertEquals(installed.template().path("id").asText(), pending.path("template_version_id").asText());
            byte[] persisted = fixture.configuration.bodyBytes(id.toString());
            var decoded = JSON.readTree(persisted);
            assertEquals(event, decoded.path("event").asText());
            assertEquals(JSON.readTree(data), decoded.path("payload"));
            assertEquals(0, fixture.mock.journal().size(), "Acceptance must not perform the business request");

            assertEquals(1, fixture.worker.tick());
            var succeeded = fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            fixture.awaitIdle(Duration.ofSeconds(5));
            assertEquals(1, fixture.mock.journal().size());
            var received = fixture.mock.journal().get(0);
            assertEquals(method, received.method());
            assertEquals(path, received.uri().getPath());
            assertArrayEquals(persisted, received.body());
            assertEquals("application/json", received.firstHeader("Content-Type"));
            assertEquals(event, received.firstHeader("X-Event-Id"));
            assertTrue("Method cp04-test-only-secret".equals(received.firstHeader("X-Api-Key")),
                    "Only the current supplier credential may populate the configured authentication header");
            assertNull(received.firstHeader("Authorization"), "Internal bearer credentials must not be forwarded");
            assertEquals(method, succeeded.path("request_method").asText());
            assertArrayEquals(persisted, fixture.configuration.bodyBytes(id.toString()));
            assertEquals(1, succeeded.path("run_attempt_count").asInt());
            assertEquals(1, succeeded.path("total_attempt_count").asInt());
            var attempts = fixture.attempts(id);
            assertEquals(1, attempts.size());
            assertEquals("HTTP_ACKNOWLEDGED", attempts.get(0).path("outcome").asText());
            assertEquals(200, attempts.get(0).path("http_status").asInt());
            assertEquals("fixture-v1", attempts.get(0).path("credential_version").asText());
            for (int scan = 0; scan < 3; scan++) {
                assertEquals(0, fixture.worker.tick());
                fixture.awaitIdle(Duration.ofSeconds(2));
            }
            assertEquals(1, fixture.mock.journal().size(), "No implicit retry or later tick may resend this terminal task");
            assertEquals(1, fixture.attempts(id).size());

            String scenario = "cp09-business-method-" + suffix;
            fixture.evidence(scenario, id, null);
            var evidence = JSON.createObjectNode().put("scenario", scenario).put("observedAt", Instant.now().toString())
                    .put("snapshotMethod", pending.path("request_method").asText()).put("actualMethod", received.method())
                    .put("bodyBytes", persisted.length).put("snapshotBytesMatched", true).put("supplierCredentialMatched", true)
                    .put("internalBearerForwarded", false).put("physicalRequests", fixture.mock.journal().size())
                    .put("attempts", fixture.attempts(id).size()).put("status", succeeded.path("status").asText());
            evidence.putArray("acceptedTaskIds").add(id.toString());
            Path directory = Path.of("target", "evidence"); Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario + "-summary.json").toFile(), evidence);
        }
    }
}
