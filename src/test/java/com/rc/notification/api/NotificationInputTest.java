package com.rc.notification.api;

import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.NotificationApiFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CP02: TC-API-03 strict input boundaries; no test substitutes the production parser. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class NotificationInputTest {
    private NotificationApiFixture api;

    @BeforeAll void start() throws Exception { api = new NotificationApiFixture(); }
    @AfterAll void stop() throws Exception { if (api != null) api.close(); }

    static Stream<Arguments> invalidJson() {
        String valid = payload("tenant-a", "user.registered", "input-invalid", "{}");
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("malformed", "{oops"));
        cases.add(Arguments.of("trailing-root", valid + " {}"));
        cases.add(Arguments.of("root-array", "[]"));
        cases.add(Arguments.of("root-null", "null"));
        cases.add(Arguments.of("unknown-field", valid.substring(0, valid.length() - 1) + ",\"secretUnexpected\":\"secret-sentinel\"}"));
        for (String field : List.of("tenantId", "operation", "eventId", "data")) {
            String missing = switch (field) {
                case "tenantId" -> "{\"operation\":\"user.registered\",\"eventId\":\"missing\",\"data\":{}}";
                case "operation" -> "{\"tenantId\":\"tenant-a\",\"eventId\":\"missing\",\"data\":{}}";
                case "eventId" -> "{\"tenantId\":\"tenant-a\",\"operation\":\"user.registered\",\"data\":{}}";
                default -> "{\"tenantId\":\"tenant-a\",\"operation\":\"user.registered\",\"eventId\":\"missing\"}";
            };
            cases.add(Arguments.of("missing-" + field, missing));
        }
        for (String data : List.of("null", "[]", "1", "\"secret-sentinel\"", "true")) {
            cases.add(Arguments.of("non-object-data-" + data, payload("tenant-a", "user.registered", "invalid", data)));
        }
        cases.add(Arguments.of("duplicate-top-key", valid.substring(0, valid.length() - 1) + ",\"tenantId\":\"tenant-a\"}"));
        cases.add(Arguments.of("duplicate-nested-key", payload("tenant-a", "user.registered", "invalid", "{\"nested\":{\"x\":1,\"x\":2}}")));
        cases.add(Arguments.of("unpaired-high-surrogate-value", payload("tenant-a", "user.registered", "invalid", "{\"x\":\"\\uD800\"}")));
        cases.add(Arguments.of("unpaired-low-surrogate-value", payload("tenant-a", "user.registered", "invalid", "{\"x\":\"\\uDC00\"}")));
        cases.add(Arguments.of("unpaired-surrogate-key", payload("tenant-a", "user.registered", "invalid", "{\"\\uD800\":1}")));
        cases.add(Arguments.of("empty-tenant", payload("", "user.registered", "invalid", "{}")));
        cases.add(Arguments.of("empty-operation", payload("tenant-a", "", "invalid", "{}")));
        for (String bad : List.of("", "space invalid", "斌", "a/b", "a\nb")) {
            cases.add(Arguments.of("invalid-event-" + bad, payload("tenant-a", "user.registered", bad, "{}")));
        }
        cases.add(Arguments.of("tenant-too-long", payload("t".repeat(65), "user.registered", "invalid", "{}")));
        cases.add(Arguments.of("operation-too-long", payload("tenant-a", "o".repeat(129), "invalid", "{}")));
        cases.add(Arguments.of("event-too-long", payload("tenant-a", "user.registered", "e".repeat(129), "{}")));
        cases.add(Arguments.of("number-too-long", payload("tenant-a", "user.registered", "invalid", "{\"n\":" + "9".repeat(1001) + "}")));
        cases.add(Arguments.of("exponent-positive-over", payload("tenant-a", "user.registered", "invalid", "{\"n\":1e10001}")));
        cases.add(Arguments.of("exponent-negative-over", payload("tenant-a", "user.registered", "invalid", "{\"n\":1e-10001}")));
        cases.add(Arguments.of("depth-over", payload("tenant-a", "user.registered", "invalid", nestedData(63))));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidJson")
    void rejectsInvalidInputWithoutPersistenceOrSensitiveEcho(String caseName, String payload) throws Exception {
        long before = api.taskCount();
        var response = api.post(payload, ADMIN);
        assertEquals(400, response.statusCode(), caseName + ": " + response.body());
        var body = api.json(response);
        assertEquals("INVALID_REQUEST", body.path("code").asText());
        assertEquals(3, body.size(), "Errors expose only code, message, and requestId");
        assertFalse(body.path("requestId").asText().isBlank());
        assertFalse(response.body().contains("secret-sentinel"));
        assertEquals(before, api.taskCount());
    }

    static Stream<Arguments> validDataBoundary() {
        return Stream.of(
                Arguments.of("number-length-1000", "{\"n\":" + "9".repeat(1000) + "}"),
                Arguments.of("positive-exponent-10000", "{\"n\":1e10000}"),
                Arguments.of("negative-exponent-10000", "{\"n\":1e-10000}"),
                Arguments.of("valid-surrogate-pair", "{\"value\":\"\\uD83D\\uDE00\"}"),
                Arguments.of("question-mark", "{\"value\":\"?\"}"),
                Arguments.of("depth-64", nestedData(62)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validDataBoundary")
    void acceptsTheSpecifiedParserBoundary(String caseName, String data) throws Exception {
        var response = api.submit(data);
        assertEquals(202, response.statusCode(), caseName + ": " + response.body());
    }

    @Test
    void acceptsMaximumIdentifierLengthsAndKeepsCaseDistinct() throws Exception {
        String tenant = "t".repeat(64);
        String operation = "o".repeat(128);
        api.route(tenant, operation);
        assertEquals(202, api.post(payload(tenant, operation, "e".repeat(128), "{}"), ADMIN).statusCode());
        String unique = "case-" + UUID.randomUUID();
        var lower = api.post(payload("tenant-a", "user.registered", unique + "a", "{}"), CALLER_A);
        var upper = api.post(payload("tenant-a", "user.registered", unique + "A", "{}"), CALLER_A);
        assertEquals(202, lower.statusCode());
        assertEquals(202, upper.statusCode());
        assertNotEquals(api.json(lower).path("notificationId"), api.json(upper).path("notificationId"));
    }

    @Test
    void rejectsMoreThan256KiBBeforeCreatingATask() throws Exception {
        long before = api.taskCount();
        String oversized = payload("tenant-a", "user.registered", "oversized", "{\"value\":\"" + "x".repeat(262144) + "\"}");
        var response = api.post(oversized, CALLER_A);
        assertEquals(413, response.statusCode(), response.body());
        assertEquals("PAYLOAD_TOO_LARGE", api.json(response).path("code").asText());
        assertEquals(before, api.taskCount());
    }

    private static String nestedData(int extraContainers) {
        return "{\"child\":".repeat(extraContainers) + "{}" + "}".repeat(extraContainers);
    }
}
