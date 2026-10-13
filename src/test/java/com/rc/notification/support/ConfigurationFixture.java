package com.rc.notification.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.rc.notification.support.NotificationApiFixture.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Calls the actual management API. Test configuration values are never real credentials. */
public final class ConfigurationFixture {
    public final NotificationApiFixture api;

    public ConfigurationFixture(NotificationApiFixture api) { this.api = api; }

    public static ObjectNode template(String tenant, String format, JsonNode fields) {
        ObjectNode value = JSON.createObjectNode();
        value.put("templateKey", "config-" + UUID.randomUUID());
        value.put("version", 1);
        value.put("ownerTenantId", tenant);
        value.put("shared", false);
        value.put("providerId", "provider-one");
        value.put("endpointGroup", "events");
        value.put("method", "POST");
        value.put("url", "https://supplier.invalid/notify");
        value.set("headers", JSON.createObjectNode());
        value.set("body", JSON.createObjectNode().put("format", format).set("fields", fields));
        return value;
    }

    public static ObjectNode scalarTemplate(String tenant, String marker) {
        return template(tenant, "json", JSON.createObjectNode().set("marker", JSON.createObjectNode().put("constant", marker)));
    }

    public static ObjectNode apiKey(String tenant) {
        return JSON.createObjectNode().put("authProfileKey", "auth-" + UUID.randomUUID()).put("version", 1)
                .put("tenantId", tenant).put("type", "api_key").put("headerName", "X-Api-Key")
                .put("prefix", "Test ").put("secretRef", "env:CP03_NEVER_RESOLVED_SECRET");
    }

    public static ObjectNode oauth(String tenant, String endpoint) {
        ObjectNode value = JSON.createObjectNode().put("authProfileKey", "oauth-" + UUID.randomUUID()).put("version", 1)
                .put("tenantId", tenant).put("type", "oauth2_client_credentials").put("tokenUrl", endpoint)
                .put("clientId", "cp03-test-client").put("clientSecretRef", "env:CP03_NEVER_RESOLVED_SECRET")
                .put("clientAuthenticationMethod", "client_secret_basic");
        value.putArray("scopes").add("notify");
        return value;
    }

    public JsonNode createTemplate(JsonNode body) throws Exception {
        return create("template-versions", body, ADMIN);
    }

    public JsonNode createAuth(JsonNode body) throws Exception {
        return create("auth-profiles", body, ADMIN);
    }

    public JsonNode create(String resource, JsonNode body, String caller) throws Exception {
        var response = api.send("POST", "/admin/" + resource, body, caller);
        assertEquals(201, response.statusCode(), response.body());
        return api.json(response);
    }

    public JsonNode publish(String resource, JsonNode version) throws Exception {
        var response = api.send("POST", "/admin/" + resource + "/" + version.path("id").asText() + "/publish",
                JSON.createObjectNode().put("expectedRevision", version.path("revision").asLong()), ADMIN);
        assertEquals(200, response.statusCode(), response.body());
        return api.json(response);
    }

    public JsonNode bind(String tenant, String operation, JsonNode template, JsonNode auth, long revision) throws Exception {
        var response = api.send("PUT", "/admin/routes/" + tenant + "/" + operation,
                routeBody(template, auth, revision), ADMIN);
        assertEquals(200, response.statusCode(), response.body());
        return api.json(response);
    }

    public static ObjectNode routeBody(JsonNode template, JsonNode auth, long revision) {
        return JSON.createObjectNode().put("expectedRevision", revision)
                .put("templateVersionId", template.path("id").asText()).put("authProfileId", auth.path("id").asText());
    }

    public Published install(ObjectNode template, ObjectNode auth, String operation) throws Exception {
        JsonNode publishedTemplate = publish("template-versions", createTemplate(template));
        JsonNode publishedAuth = publish("auth-profiles", createAuth(auth));
        JsonNode route = bind(auth.path("tenantId").asText(), operation, publishedTemplate, publishedAuth, 0);
        return new Published(publishedTemplate, publishedAuth, route);
    }

    public byte[] bodyBytes(String notificationId) throws Exception {
        try (var connection = api.database.connection(); var query = connection.prepareStatement(
                "SELECT body_bytes FROM " + api.database.schema() + ".notification_task WHERE id=?")) {
            query.setObject(1, UUID.fromString(notificationId));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Task does not exist: " + notificationId);
                return rows.getBytes(1);
            }
        }
    }

    public record Published(JsonNode template, JsonNode auth, JsonNode route) { }
}
