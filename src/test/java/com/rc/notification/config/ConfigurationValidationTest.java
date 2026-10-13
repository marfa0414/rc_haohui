package com.rc.notification.config;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.NotificationApiFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP03 TC-CFG-08, TC-MAP-04/05/06: invalid configuration cannot become executable. */
@Timeout(60)
class ConfigurationValidationTest {
    static Stream<Arguments> invalidTemplates() {
        List<Arguments> cases = new ArrayList<>();
        add(cases, "unknown-encoder", value -> ((ObjectNode) value.path("body")).put("format", "xml"));
        add(cases, "both-from-and-constant", value -> leaf(value).put("from", "data.marker"));
        add(cases, "neither-from-nor-constant", value -> leaf(value).remove("constant"));
        for (String from : List.of("System.getenv('SECRET')", "data.items[0]", "#{systemProperties}", "data..x", "tenantId")) {
            add(cases, "invalid-path-" + from, value -> { leaf(value).remove("constant"); leaf(value).put("from", from).put("type", "string"); });
        }
        add(cases, "unknown-type", value -> leaf(value).put("type", "java.lang.Runtime"));
        add(cases, "nested-form-output", value -> {
            ((ObjectNode) value.path("body")).put("format", "form");
            leaf(value).remove("constant");
            leaf(value).set("fields", JSON.createObjectNode().set("nested", JSON.createObjectNode().put("constant", "x")));
        });
        for (String constant : List.of("null", "\"\"", "{}", "[]")) {
            add(cases, "form-nonscalar-" + constant, value -> {
                ((ObjectNode) value.path("body")).put("format", "form");
                try { leaf(value).set("constant", JSON.readTree(constant)); } catch (Exception impossible) { throw new AssertionError(impossible); }
            });
        }
        for (String method : List.of("GET", "DELETE", "SCRIPT")) add(cases, "unsupported-method-" + method, value -> value.put("method", method));
        for (String url : List.of("file:///tmp/anything", "https://user:pass@supplier.invalid/path", "https://supplier.invalid/path#fragment",
                "https://supplier.invalid/${data.id}", "https://unapproved.invalid/notify")) {
            add(cases, "invalid-url-" + url, value -> value.put("url", url));
        }
        for (String header : List.of("authorization", "AUTHORIZATION", "hOsT", "Content-Length", "Connection", "Transfer-Encoding", "content-type")) {
            add(cases, "reserved-header-" + header, value -> value.set("headers", JSON.createObjectNode().set(header, JSON.createObjectNode().put("constant", "x"))));
        }
        add(cases, "nonstring-header", value -> value.set("headers", JSON.createObjectNode().set("X-Number", JSON.createObjectNode().put("constant", 1))));
        add(cases, "newline-header-name", value -> value.set("headers", JSON.createObjectNode().set("X-Safe\r\nInjected", JSON.createObjectNode().put("constant", "x"))));
        add(cases, "newline-header-value", value -> value.set("headers", JSON.createObjectNode().set("X-Safe", JSON.createObjectNode().put("constant", "x\r\nInjected: true"))));
        for (int control : List.of(0, 7, 31, 127)) {
            add(cases, "control-header-value-" + control, value -> value.set("headers", JSON.createObjectNode()
                    .set("X-Safe", JSON.createObjectNode().put("constant", "before" + (char) control + "after"))));
        }
        add(cases, "case-colliding-headers", value -> {
            var headers = JSON.createObjectNode();
            headers.set("X-Duplicate", JSON.createObjectNode().put("constant", "one"));
            headers.set("x-duplicate", JSON.createObjectNode().put("constant", "two"));
            value.set("headers", headers);
        });
        return cases.stream();
    }

    private static void add(List<Arguments> cases, String name, Consumer<ObjectNode> mutation) {
        cases.add(Arguments.of(name, mutation));
    }

    private static ObjectNode leaf(ObjectNode value) { return (ObjectNode) value.path("body").path("fields").path("marker"); }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidTemplates")
    void invalidTemplatesCannotBePublished(String name, Consumer<ObjectNode> mutation) throws Exception {
        try (var api = new NotificationApiFixture()) {
            var input = scalarTemplate("tenant-a", "valid-start");
            mutation.accept(input);
            var created = api.send("POST", "/admin/template-versions", input, ADMIN);
            if (created.statusCode() == 400) {
                assertEquals("INVALID_CONFIGURATION", api.json(created).path("code").asText(), name);
            } else {
                assertEquals(201, created.statusCode(), name + ": " + created.body());
                var draft = api.json(created);
                String path = "/admin/template-versions/" + draft.path("id").asText();
                var invalid = api.send("POST", path + "/validate", JSON.createObjectNode(), ADMIN);
                assertEquals(400, invalid.statusCode(), name + ": " + invalid.body());
                assertEquals("INVALID_CONFIGURATION", api.json(invalid).path("code").asText());
                var publish = api.send("POST", path + "/publish",
                        JSON.createObjectNode().put("expectedRevision", draft.path("revision").asLong()), ADMIN);
                assertEquals(400, publish.statusCode(), name + ": " + publish.body());
                assertEquals("draft", api.json(api.get(path, ADMIN)).path("status").asText());
            }
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void unknownAuthAndUnsafeCredentialConfigurationCannotPublish() throws Exception {
        try (var api = new NotificationApiFixture()) {
            List<ObjectNode> invalid = new ArrayList<>(List.of(
                    apiKey("tenant-a").put("type", "custom-java"),
                    apiKey("tenant-a").put("secretRef", "plaintext-secret-sentinel"),
                    apiKey("tenant-a").put("headerName", "Host"),
                    apiKey("tenant-a").put("prefix", "prefix\r\nInjected: x"),
                    oauth("tenant-a", "https://oauth.invalid/token").put("clientAuthenticationMethod", "password"),
                    oauth("tenant-a", "https://oauth.invalid/token").put("clientId", "")));
            for (int control : List.of(0, 7, 31, 127)) {
                invalid.add(apiKey("tenant-a").put("prefix", "before" + (char) control + "after"));
            }
            for (var input : invalid) {
                var response = api.send("POST", "/admin/auth-profiles", input, ADMIN);
                if (response.statusCode() == 201) {
                    var draft = api.json(response);
                    var publication = api.send("POST", "/admin/auth-profiles/" + draft.path("id").asText() + "/publish",
                            JSON.createObjectNode().put("expectedRevision", draft.path("revision").asLong()), ADMIN);
                    assertEquals(400, publication.statusCode(), publication.body());
                    assertFalse(publication.body().contains("plaintext-secret-sentinel"));
                } else {
                    assertEquals(400, response.statusCode(), response.body());
                    assertFalse(response.body().contains("plaintext-secret-sentinel"));
                }
            }
        }
    }

    @Test
    void customApiKeyHeaderCannotConflictWithAnOrdinaryHeaderIgnoringCase() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var input = scalarTemplate("tenant-a", "conflict");
            input.set("headers", JSON.createObjectNode().set("x-api-key", JSON.createObjectNode().put("constant", "forged")));
            var template = config.publish("template-versions", config.createTemplate(input));
            var auth = config.publish("auth-profiles", config.createAuth(apiKey("tenant-a")));
            var result = api.send("PUT", "/admin/routes/tenant-a/header.conflict", routeBody(template, auth, 0), ADMIN);
            assertEquals(400, result.statusCode(), result.body());
            assertEquals(0, ConfigurationLifecycleTest.count(api, "notification_route", "operation='header.conflict'"));
        }
    }
}
