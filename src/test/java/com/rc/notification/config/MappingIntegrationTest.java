package com.rc.notification.config;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.NotificationApiFixture;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP03 TC-MAP-01..08 mapping/byte snapshots; physical delivery remains CP04. */
@Timeout(60)
class MappingIntegrationTest {
    private static final ObjectMapper LOSSLESS = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    @Test
    void jsonSupportsNestedObjectsDirectArraysAndExactScalarTypes() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            JsonNode fields = JSON.readTree("""
                    {"profile":{"fields":{"name":{"from":"data.name","type":"string","required":true},
                                             "number":{"from":"data.number","type":"number","required":true}}},
                     "items":{"from":"data.items","type":"array","required":true},
                     "direct":{"from":"data.direct","type":"object","required":true},
                     "zero":{"from":"data.zero","type":"number","required":true},
                     "flag":{"from":"data.flag","type":"boolean","required":true},
                     "nothing":{"constant":null},"event":{"from":"eventId","type":"string","required":true}}
                    """);
            config.install(template("tenant-a", "json", fields), apiKey("tenant-a"), "mapped.json");
            String data = "{\"name\":\"中文\\n\\\"quoted\\\"\",\"number\":0.123456789012345678901234567890,"
                    + "\"items\":[1,\"x\",true],\"direct\":{\"large\":123456789012345678901234567890},\"zero\":0,\"flag\":false}";
            var accepted = api.post(payload("tenant-a", "mapped.json", "mapping-event", data), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var output = LOSSLESS.readTree(config.bodyBytes(api.json(accepted).path("notificationId").asText()));
            assertTrue(output.isObject(), "Body must be a JSON object, never JSON containing another encoded JSON string");
            assertEquals("中文\n\"quoted\"", output.path("profile").path("name").asText());
            assertEquals(0, new BigDecimal("0.123456789012345678901234567890").compareTo(output.path("profile").path("number").decimalValue()));
            assertEquals("123456789012345678901234567890", output.path("direct").path("large").asText());
            assertTrue(output.path("items").isArray());
            assertEquals(0, output.path("zero").asInt(-1));
            assertTrue(output.path("flag").isBoolean());
            assertFalse(output.path("flag").asBoolean());
            assertTrue(output.path("nothing").isNull());
            assertEquals("mapping-event", output.path("event").asText());
        }
    }

    @Test
    void secondSupplierUsesOnlyFormConfigurationAndEncodesSpecialCharactersOnce() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            config.install(scalarTemplate("tenant-a", "json-supplier"), apiKey("tenant-a"), "mapped.json");
            var form = template("tenant-a", "form", JSON.readTree("""
                    {"name":{"from":"data.name","type":"string","required":true},
                     "zero":{"constant":0},"flag":{"constant":false},"event":{"from":"eventId","type":"string","required":true}}
                    """));
            form.put("providerId", "provider-two").put("url", "https://supplier-two.invalid/notify");
            config.install(form, apiKey("tenant-a"), "mapped.form");
            var accepted = api.post(payload("tenant-a", "mapped.form", "form-event", "{\"name\":\"中 文&=+%/\\n\"}"), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            String id = api.json(accepted).path("notificationId").asText();
            String body = new String(config.bodyBytes(id), StandardCharsets.UTF_8);
            Map<String, String> decoded = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                decoded.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            assertEquals(Map.of("name", "中 文&=+%/\n", "zero", "0", "flag", "false", "event", "form-event"), decoded);
            try (var connection = api.database.connection(); var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT content_type,provider_id FROM " + api.database.schema()
                            + ".notification_task WHERE id='" + id + "'")) {
                assertTrue(rows.next());
                assertEquals("application/x-www-form-urlencoded", rows.getString(1));
                assertEquals("provider-two", rows.getString(2));
            }
        }
    }

    static Stream<Arguments> invalidRequiredValues() {
        return Stream.of(Arguments.of("missing", "{}"), Arguments.of("null", "{\"value\":null}"),
                Arguments.of("number-not-string", "{\"value\":12}"), Arguments.of("object-not-string", "{\"value\":{}}"),
                Arguments.of("array-not-string", "{\"value\":[]}"));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidRequiredValues")
    void missingNullAndWrongTypeAreRejectedBeforeAcceptance(String name, String data) throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            config.install(template("tenant-a", "json", JSON.readTree("{\"value\":{\"from\":\"data.value\",\"type\":\"string\",\"required\":true}}")),
                    apiKey("tenant-a"), "mapped.required");
            var response = api.post(payload("tenant-a", "mapped.required", "invalid-required", data), ADMIN);
            assertEquals(400, response.statusCode(), name + ": " + response.body());
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void optionalNullAndMissingAreOmittedButFalseAndZeroRemain() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            config.install(template("tenant-a", "json", JSON.readTree("""
                    {"absent":{"from":"data.absent","type":"string"},"nothing":{"from":"data.nothing","type":"string"},
                     "zero":{"from":"data.zero","type":"number"},"flag":{"from":"data.flag","type":"boolean"}}
                    """)), apiKey("tenant-a"), "mapped.optional");
            var response = api.post(payload("tenant-a", "mapped.optional", "optional", "{\"nothing\":null,\"zero\":0,\"flag\":false}"), ADMIN);
            assertEquals(202, response.statusCode(), response.body());
            assertEquals(JSON.readTree("{\"zero\":0,\"flag\":false}"), JSON.readTree(config.bodyBytes(api.json(response).path("notificationId").asText())));
        }
    }

    @Test
    void formRejectsEmptyStringNullAndNonScalarValues() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            config.install(template("tenant-a", "form", JSON.readTree("{\"value\":{\"from\":\"data.value\",\"type\":\"string\",\"required\":true}}")),
                    apiKey("tenant-a"), "mapped.form-invalid");
            for (String value : List.of("\"\"", "null", "{}", "[]")) {
                var response = api.post(payload("tenant-a", "mapped.form-invalid", "invalid-" + UUID.randomUUID(), "{\"value\":" + value + "}"), ADMIN);
                assertEquals(400, response.statusCode(), value + ": " + response.body());
            }
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void outputBodyBoundaryUsesUtf8BytesAndRejectsExpansionBeforePersistence() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            ObjectNode fields = (ObjectNode) JSON.readTree("{\"a\":{\"from\":\"data.value\",\"type\":\"string\"},\"b\":{\"from\":\"data.value\",\"type\":\"string\"},\"pad\":{\"constant\":\"\"}}");
            int emptyBytes = "{\"a\":\"\",\"b\":\"\",\"pad\":\"\"}".getBytes(StandardCharsets.UTF_8).length;
            int padding = (262144 - emptyBytes) % 2;
            ((ObjectNode) fields.path("pad")).put("constant", "x".repeat(padding));
            int valueBytes = (262144 - emptyBytes - padding) / 2;
            String value = utf8String(valueBytes);
            config.install(template("tenant-a", "json", fields), apiKey("tenant-a"), "mapped.body-boundary");
            ObjectNode overflow = fields.deepCopy();
            ((ObjectNode) overflow.path("pad")).put("constant", "x".repeat(padding + 1));
            config.install(template("tenant-a", "json", overflow), apiKey("tenant-a"), "mapped.body-overflow");
            String data = JSON.createObjectNode().put("value", value).toString();
            var accepted = api.post(payload("tenant-a", "mapped.body-boundary", "body-exact", data), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            assertEquals(262144, config.bodyBytes(api.json(accepted).path("notificationId").asText()).length);
            var rejected = api.post(payload("tenant-a", "mapped.body-overflow", "body-over", data), ADMIN);
            assertEquals(422, rejected.statusCode(), rejected.body());
            assertEquals(1, api.taskCount());
        }
    }

    @Test
    void ordinaryHeaderBoundaryUsesUtf8BytesAndDynamicValuesCannotInjectLines() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var template = scalarTemplate("tenant-a", "header-boundary");
            template.set("headers", JSON.readTree("{\"X-Boundary\":{\"from\":\"data.value\",\"type\":\"string\",\"required\":true}}"));
            config.install(template, apiKey("tenant-a"), "mapped.header-boundary");
            int valueBytes = 16384 - "X-Boundary".getBytes(StandardCharsets.UTF_8).length - 4;
            String exact = JSON.createObjectNode().put("value", utf8String(valueBytes)).toString();
            assertEquals(202, api.post(payload("tenant-a", "mapped.header-boundary", "header-exact", exact), ADMIN).statusCode());
            var tooLarge = api.post(payload("tenant-a", "mapped.header-boundary", "header-over",
                    JSON.createObjectNode().put("value", utf8String(valueBytes + 1)).toString()), ADMIN);
            assertEquals(422, tooLarge.statusCode(), tooLarge.body());
            for (String invalid : List.of("safe\r\nX-Injected: yes", "safe\nnewline", "before" + (char) 0 + "after",
                    "before" + (char) 7 + "after", "before" + (char) 31 + "after", "before" + (char) 127 + "after")) {
                var response = api.post(payload("tenant-a", "mapped.header-boundary", "header-over-" + UUID.randomUUID(),
                        JSON.createObjectNode().put("value", invalid).toString()), ADMIN);
                assertEquals(400, response.statusCode(), response.body());
            }
            assertEquals(202, api.post(payload("tenant-a", "mapped.header-boundary", "header-tab",
                    JSON.createObjectNode().put("value", "safe\ttab").toString()), ADMIN).statusCode());
            var constantTab = scalarTemplate("tenant-a", "constant-tab");
            constantTab.set("headers", JSON.createObjectNode().set("X-Tab", JSON.createObjectNode().put("constant", "safe\ttab")));
            config.install(constantTab, apiKey("tenant-a"), "mapped.header-tab");
            assertEquals(202, api.post(payload("tenant-a", "mapped.header-tab", "constant-header-tab", "{}"), ADMIN).statusCode());
            assertEquals(3, api.taskCount());
        }
    }

    @Test
    void jsonBusinessStringMayContainAnEscapedNulWithoutHeaderPolicyRejectingIt() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            config.install(template("tenant-a", "json", JSON.readTree("{\"value\":{\"from\":\"data.value\",\"type\":\"string\",\"required\":true}}")),
                    apiKey("tenant-a"), "mapped.body-nul");
            String value = "before" + (char) 0 + "after";
            var response = api.post(payload("tenant-a", "mapped.body-nul", "body-nul",
                    JSON.createObjectNode().put("value", value).toString()), ADMIN);
            assertEquals(202, response.statusCode(), response.body());
            assertEquals(value, JSON.readTree(config.bodyBytes(api.json(response).path("notificationId").asText())).path("value").asText());
        }
    }

    private static String utf8String(int bytes) { return "中".repeat(bytes / 3) + "x".repeat(bytes % 3); }
}
