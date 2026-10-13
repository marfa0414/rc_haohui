package com.rc.notification.config;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP03 TC-CFG-01..04, TC-MAP-08, delivery examples. */
@Timeout(60)
class ConfigurationLifecycleTest {
    @Test
    void draftsMustPublishBeforeBindingAndPublishedResourcesAndSnapshotsAreImmutable() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            ObjectNode templateInput = scalarTemplate("tenant-a", "original");
            ObjectNode authInput = apiKey("tenant-a");
            var template = config.createTemplate(templateInput);
            var auth = config.createAuth(authInput);
            assertEquals("draft", template.path("status").asText());
            assertEquals(1, template.path("revision").asLong());
            var validation = api.send("POST", "/admin/template-versions/" + template.path("id").asText() + "/validate", JSON.createObjectNode(), ADMIN);
            assertEquals(200, validation.statusCode(), validation.body());
            long auditBeforeRejectedBinding = count(api, "audit_event", "true");
            var beforePublish = api.send("PUT", "/admin/routes/tenant-a/config.lifecycle", routeBody(template, auth, 0), ADMIN);
            assertEquals(400, beforePublish.statusCode());
            assertEquals(auditBeforeRejectedBinding, count(api, "audit_event", "true"));
            template = config.publish("template-versions", template);
            auth = config.publish("auth-profiles", auth);
            config.bind("tenant-a", "config.lifecycle", template, auth, 0);
            var accepted = api.post(payload("tenant-a", "config.lifecycle", "immutable-event", "{}"), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            String taskId = api.json(accepted).path("notificationId").asText();
            byte[] originalBytes = config.bodyBytes(taskId);
            long auditCount = count(api, "audit_event", "true");
            ObjectNode editedTemplate = templateInput.deepCopy().put("expectedRevision", template.path("revision").asLong());
            editedTemplate.put("description", "cannot-edit-published");
            assertEquals(409, api.send("PUT", "/admin/template-versions/" + template.path("id").asText(), editedTemplate, ADMIN).statusCode());
            ObjectNode editedAuth = authInput.deepCopy().put("expectedRevision", auth.path("revision").asLong()).put("prefix", "changed ");
            assertEquals(409, api.send("PUT", "/admin/auth-profiles/" + auth.path("id").asText(), editedAuth, ADMIN).statusCode());
            assertEquals(409, api.send("POST", "/admin/template-versions/" + template.path("id").asText() + "/publish",
                    JSON.createObjectNode().put("expectedRevision", template.path("revision").asLong()), ADMIN).statusCode());
            assertEquals(auditCount, count(api, "audit_event", "true"), "Rejected changes must not fabricate successful audit entries");
            for (String tableAndId : List.of("notification_template_version|" + template.path("id").asText(),
                    "auth_profile|" + auth.path("id").asText())) {
                String[] parts = tableAndId.split("\\|");
                var updateRejected = assertThrows(SQLException.class, () -> api.sql("UPDATE " + parts[0] + " SET revision=revision+1 WHERE id='" + parts[1] + "'"));
                assertEquals("23514", updateRejected.getSQLState(), "Published immutability must reject the update");
                var deleteRejected = assertThrows(SQLException.class, () -> api.sql("DELETE FROM " + parts[0] + " WHERE id='" + parts[1] + "'"));
                assertEquals("23514", deleteRejected.getSQLState(),
                        "Deleting published configuration must fail its immutable trigger, not merely an existing foreign key (23503)");
            }
            assertThrows(SQLException.class, () -> api.sql("UPDATE notification_task SET body_bytes=convert_to('changed','UTF8') WHERE id='" + taskId + "'"));
            assertArrayEquals(originalBytes, config.bodyBytes(taskId));
            assertTrue(auditCount >= 5, "Create/publish/bind actions must be attributable");
            assertEquals(0, count(api, "audit_event", "actor_id IS NULL OR actor_id=''"));
            assertThrows(SQLException.class, () -> api.sql("UPDATE audit_event SET action='tampered'"));
            assertThrows(SQLException.class, () -> api.sql("DELETE FROM audit_event"));
        }
    }

    @Test
    void staleDraftRevisionCannotBePublishedAndConcurrentEditsHaveOneWinner() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var original = scalarTemplate("tenant-a", "revision");
            var draft = config.createTemplate(original);
            var one = original.deepCopy().put("expectedRevision", 1).put("description", "first-editor");
            var two = original.deepCopy().put("expectedRevision", 1).put("description", "second-editor");
            var responses = race(api, "/admin/template-versions/" + draft.path("id").asText(), one, two);
            assertEquals(List.of(200, 409), responses.stream().map(java.net.http.HttpResponse::statusCode).sorted().toList());
            String path = "/admin/template-versions/" + draft.path("id").asText();
            assertEquals(409, api.send("POST", path + "/publish", JSON.createObjectNode().put("expectedRevision", 1), ADMIN).statusCode());
            var current = api.json(api.get(path, ADMIN));
            assertEquals(2, current.path("revision").asLong());
            assertEquals("draft", current.path("status").asText());
            assertEquals("published", config.publish("template-versions", current).path("status").asText());

            var authInput = apiKey("tenant-a");
            var auth = config.createAuth(authInput);
            var updated = api.send("PUT", "/admin/auth-profiles/" + auth.path("id").asText(),
                    authInput.deepCopy().put("expectedRevision", 1).put("prefix", "new-prefix "), ADMIN);
            assertEquals(200, updated.statusCode(), updated.body());
            assertEquals(409, api.send("POST", "/admin/auth-profiles/" + auth.path("id").asText() + "/publish",
                    JSON.createObjectNode().put("expectedRevision", 1), ADMIN).statusCode());
            assertEquals("published", config.publish("auth-profiles", api.json(updated)).path("status").asText());
        }
    }

    @Test
    void previewAndOAuthPublicationAreOfflineAndRedactAllRenderedValues() throws Exception {
        try (var mock = new HttpMock()) {
            mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "should-not-be-called"));
            mock.route("/token", exchange -> HttpMock.respond(exchange, 200, "{\"access_token\":\"should-not-be-obtained\"}"));
            try (var api = new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                    "app.environment", "test", "app.outbound.allowed-hosts", "127.0.0.1,supplier.invalid",
                    "app.outbound.development-http-hosts", "127.0.0.1",
                    "app.outbound.development-http-ports", Integer.toString(mock.uri("/notify").getPort())))) {
                var config = new ConfigurationFixture(api);
                var input = template("tenant-a", "json", JSON.readTree("{\"person\":{\"from\":\"data.name\",\"type\":\"string\",\"required\":true}}"));
                input.put("url", mock.uri("/notify").toString());
                input.set("headers", JSON.readTree("{\"X-Custom\":{\"constant\":\"header-private-sentinel\"}}"));
                var draft = config.createTemplate(input);
                var auth = config.createAuth(oauth("tenant-a", mock.uri("/token").toString()));
                var preview = api.send("POST", "/admin/template-versions/" + draft.path("id").asText() + "/preview",
                        JSON.createObjectNode().put("eventId", "preview-event").set("data", JSON.createObjectNode().put("name", "body-private-sentinel")), ADMIN);
                assertEquals(200, preview.statusCode(), preview.body());
                assertFalse(preview.body().contains("body-private-sentinel"));
                assertFalse(preview.body().contains("header-private-sentinel"));
                assertEquals("***", api.json(preview).path("headers").path("X-Custom").asText());
                assertEquals("***", api.json(preview).path("bodyPreview").path("person").asText());
                assertTrue(api.json(preview).path("bodyBytes").asInt() > 0);
                config.publish("template-versions", draft);
                config.publish("auth-profiles", auth);
                assertEquals(0, mock.journal().size(), "Preview, validation and publication must not call a business or token endpoint");
                assertEquals(0, api.taskCount());
            }
        }
    }

    @Test
    void copyingWithOnlyDescriptionChangesKeepsExecutionBytesAndLeavesTheOldVersionAlone() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var input = scalarTemplate("tenant-a", "description-independent");
            var first = config.install(input, apiKey("tenant-a"), "copy.description");
            ObjectNode copy = JSON.createObjectNode().put("copyFromId", first.template().path("id").asText())
                    .put("templateKey", input.path("templateKey").asText()).put("version", 2)
                    .put("ownerTenantId", "tenant-a").put("shared", false).put("description", "new documentation only");
            var second = config.publish("template-versions", config.createTemplate(copy));
            String event = payload("tenant-a", "copy.description", "before-copy", "{}");
            var before = api.post(event, ADMIN);
            assertEquals(202, before.statusCode(), before.body());
            byte[] original = config.bodyBytes(api.json(before).path("notificationId").asText());
            config.bind("tenant-a", "copy.description", second, first.auth(), first.route().path("revision").asLong());
            var after = api.post(payload("tenant-a", "copy.description", "after-copy", "{}"), ADMIN);
            assertEquals(202, after.statusCode(), after.body());
            assertArrayEquals(original, config.bodyBytes(api.json(after).path("notificationId").asText()));
            assertArrayEquals(original, config.bodyBytes(api.json(before).path("notificationId").asText()));
            var oldVersion = api.json(api.get("/admin/template-versions/" + first.template().path("id").asText(), ADMIN));
            assertEquals(1, oldVersion.path("version").asInt());
            assertNotEquals("new documentation only", oldVersion.path("description").asText());
        }
    }

    @Test
    void checkedInCrmAndAdsExamplesAreAcceptedThroughConfigurationOnly() throws Exception {
        try (var api = new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                "app.outbound.allowed-hosts", "supplier.invalid,crm.example.com,ads.example.com"))) {
            var config = new ConfigurationFixture(api);
            for (String supplier : List.of("crm", "ads")) {
                Path directory = Path.of("examples/configuration");
                var template = (ObjectNode) JSON.readTree(Files.readString(directory.resolve(supplier + "-template.json")));
                var auth = (ObjectNode) JSON.readTree(Files.readString(directory.resolve(supplier + "-auth.json")));
                var event = JSON.readTree(Files.readString(directory.resolve(supplier + "-event.json")));
                var publishedTemplate = config.publish("template-versions", config.createTemplate(template));
                var publishedAuth = config.publish("auth-profiles", config.createAuth(auth));
                String tenant = event.path("tenantId").asText();
                String operation = event.path("operation").asText();
                var existingRoute = api.get("/admin/routes/" + tenant + "/" + operation, ADMIN);
                assertTrue(existingRoute.statusCode() == 200 || existingRoute.statusCode() == 404, existingRoute.body());
                long expectedRevision = existingRoute.statusCode() == 200 ? api.json(existingRoute).path("revision").asLong() : 0;
                config.bind(tenant, operation, publishedTemplate, publishedAuth, expectedRevision);
                var response = api.post(event.toString(), ADMIN);
                assertEquals(202, response.statusCode(), supplier + ": " + response.body());
                byte[] body = config.bodyBytes(api.json(response).path("notificationId").asText());
                if (supplier.equals("crm")) {
                    assertEquals("contact-123", JSON.readTree(body).path("contact").path("id").asText());
                } else {
                    assertTrue(new String(body, StandardCharsets.UTF_8).contains("registered=true"));
                }
            }
            assertEquals(2, api.taskCount());
        }
    }

    @Test
    void largeButAllowedNumericConstantSurvivesTheRealJsonbRoundTrip() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var input = scalarTemplate("tenant-a", "replaced-by-number");
            ((ObjectNode) input.path("body").path("fields").path("marker")).put("constant", new BigDecimal("1e10000"));
            var published = config.install(input, apiKey("tenant-a"), "config.large-number");
            var response = api.post(payload("tenant-a", "config.large-number", "large-number-event", "{}"), ADMIN);
            assertEquals(202, response.statusCode(), response.body());
            var boundedReader = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                    StreamReadConstraints.builder().maxNumberLength(262144).build()).build())
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            var storedBody = boundedReader.readTree(config.bodyBytes(api.json(response).path("notificationId").asText()));
            assertEquals(0, new BigDecimal("1e10000").compareTo(storedBody.path("marker").decimalValue()));
            var preview = api.send("POST", "/admin/template-versions/" + published.template().path("id").asText() + "/preview",
                    JSON.createObjectNode().put("eventId", "large-preview").set("data", JSON.createObjectNode()), ADMIN);
            assertEquals(200, preview.statusCode(), preview.body());
            assertEquals("***", api.json(preview).path("bodyPreview").path("marker").asText());
        }
    }

    @Test
    void configurationJsonbExpansionIsBoundedBeforeCreateOrUpdateCanCommit() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var input = scalarTemplate("tenant-a", "expansion-start");
            var fields = JSON.createObjectNode();
            for (int index = 0; index < 30; index++) {
                fields.set("value" + index, JSON.createObjectNode().put("constant", new BigDecimal("1e10000")));
            }
            var oversized = input.deepCopy();
            ((ObjectNode) oversized.path("body")).set("fields", fields);
            assertTrue(oversized.toString().getBytes(StandardCharsets.UTF_8).length < 262144,
                    "The original HTTP JSON is small; PostgreSQL decimal expansion is the boundary under test");
            long templates = count(api, "notification_template_version", "true");
            long audits = count(api, "audit_event", "true");
            var rejected = api.send("POST", "/admin/template-versions", oversized, ADMIN);
            assertEquals(400, rejected.statusCode(), rejected.body());
            assertEquals("INVALID_CONFIGURATION", api.json(rejected).path("code").asText());
            assertEquals(templates, count(api, "notification_template_version", "true"));
            assertEquals(audits, count(api, "audit_event", "true"));
            var draft = config.createTemplate(input);
            audits = count(api, "audit_event", "true");
            oversized.put("expectedRevision", draft.path("revision").asLong());
            var updated = api.send("PUT", "/admin/template-versions/" + draft.path("id").asText(), oversized, ADMIN);
            assertEquals(400, updated.statusCode(), updated.body());
            var unchanged = api.json(api.get("/admin/template-versions/" + draft.path("id").asText(), ADMIN));
            assertEquals(draft.path("revision"), unchanged.path("revision"));
            assertEquals("expansion-start", unchanged.path("body").path("fields").path("marker").path("constant").asText());
            assertEquals(audits, count(api, "audit_event", "true"));
        }
    }

    private static List<java.net.http.HttpResponse<String>> race(NotificationApiFixture api, String path,
            ObjectNode one, ObjectNode two) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("PUT", path, one, ADMIN); });
            var second = pool.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("PUT", path, two, ADMIN); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    static long count(NotificationApiFixture api, String table, String predicate) throws Exception {
        try (var connection = api.database.connection(); var query = connection.createStatement();
                var rows = query.executeQuery("SELECT count(*) FROM " + api.database.schema() + "." + table + " WHERE " + predicate)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
