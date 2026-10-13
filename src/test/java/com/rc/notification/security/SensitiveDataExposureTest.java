package com.rc.notification.security;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** CP08 TC-SEC-05, TC-OBS-01/04: capture actual application logs, HTTP responses and committed database rows. */
@Timeout(60)
class SensitiveDataExposureTest {
    private static final String BODY = "cp08-body-private-9e8b5a";
    private static final String HEADER = "cp08-header-private-e153c2";
    private static final String TOKEN = "cp08.opaque.token.private.412bc8";
    private static final String RESPONSE = "cp08-vendor-response-private-519d2d";
    private static final String TOKEN_ERROR = "cp08-token-error-description-private-bcb142";

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void successBusinessRejectionAndTokenErrorsExposeOnlySafeMetadata(boolean tokenError) throws Exception {
        try (var f = new OAuthFixture(Map.of(), "client_secret_basic"); var logs = new CapturedLogs()) {
            f.tokens.route("/token", e -> HttpMock.respond(e, tokenError ? 400 : 200, tokenError
                    ? JSON.createObjectNode().put("error", "invalid_client").put("error_description", TOKEN_ERROR).toString()
                    : OAuthFixture.response(TOKEN, 300)));
            f.worker.mock.route("/private", e -> HttpMock.respond(e,
                    e.getRequestHeaders().getFirst("X-Event-Id").endsWith("rejected") ? 403 : 200, RESPONSE));
            var definition = template("tenant-a", "json", JSON.readTree("{\"privateValue\":{\"from\":\"data.privateValue\",\"required\":true,\"type\":\"string\"}}"))
                    .put("providerId", "privacy-provider").put("url", f.worker.mock.uri("/private").toString());
            definition.set("headers", JSON.readTree("{\"X-Private\":{\"from\":\"data.privateHeader\",\"type\":\"string\"},\"X-Event-Id\":{\"from\":\"eventId\",\"type\":\"string\"}}"));
            var draft = f.worker.configuration.createTemplate(definition);
            String templatePath = "/admin/template-versions/" + draft.path("id").asText();
            var eventData = JSON.createObjectNode().put("privateValue", BODY).put("privateHeader", HEADER);
            var preview = f.worker.api.send("POST", templatePath + "/preview",
                    JSON.createObjectNode().put("eventId", "preview").set("data", eventData), CONFIG_A);
            assertEquals(200, preview.statusCode());
            assertEquals("***", f.worker.api.json(preview).path("bodyPreview").path("privateValue").asText());
            assertEquals(0, f.tokens.journal().size()); assertEquals(0, f.worker.mock.journal().size());
            var published = f.worker.configuration.publish("template-versions", draft);
            var route = f.worker.configuration.bind("tenant-a", "privacy.notify", published, f.auth, 0);
            // A second revision and rollback are real authorized writes; audit records keep only identity/revision summaries.
            var replacement = f.worker.configuration.publish("template-versions", f.worker.configuration.createTemplate(definition.deepCopy().put("templateKey", "privacy-replacement-" + UUID.randomUUID())));
            var switched = f.worker.configuration.bind("tenant-a", "privacy.notify", replacement, f.auth, route.path("revision").asLong());
            f.worker.configuration.bind("tenant-a", "privacy.notify", published, f.auth, switched.path("revision").asLong());
            var publicResponses = new ArrayList<String>(); publicResponses.add(preview.body());
            var ids = new ArrayList<UUID>(); var requestIds = new ArrayList<String>();
            for (String suffix : tokenError ? List.of("token-failed") : List.of("success", "rejected")) {
                var accepted = f.worker.api.post(payload("tenant-a", "privacy.notify", "privacy-" + suffix, eventData.toString()), ADMIN);
                assertEquals(202, accepted.statusCode()); publicResponses.add(accepted.body());
                var id = UUID.fromString(f.worker.api.json(accepted).path("notificationId").asText()); ids.add(id);
                String requestId = accepted.headers().firstValue("X-Request-Id").orElseThrow(); UUID.fromString(requestId); requestIds.add(requestId);
                assertEquals(1, f.worker.worker.tick()); f.worker.awaitIdle(Duration.ofSeconds(6));
                var state = f.worker.task(id);
                assertEquals(tokenError || suffix.equals("rejected") ? "failed" : "succeeded", state.path("status").asText());
                if (tokenError) { assertEquals("NOT_SENT", state.path("last_outcome").asText()); assertEquals("TOKEN_INVALID_CLIENT", state.path("last_error_code").asText()); }
                for (String path : List.of("/v1/notifications/" + id, "/v1/notifications/" + id + "/attempts")) {
                    var response = f.worker.api.get(path, OPERATOR_A); assertEquals(200, response.statusCode()); publicResponses.add(response.body());
                }
                // A necessary business snapshot retains supplied business fields, but never resolved authentication.
                assertEquals(JSON.createObjectNode().put("privateValue", BODY), JSON.readTree(f.worker.configuration.bodyBytes(id.toString())));
                assertTrue(state.path("ordinary_headers").toString().contains(HEADER));
                assertAbsent(state.toString(), authenticationSentinels(), "persisted task");
                assertAbsent(f.worker.attempts(id).toString(), sensitiveSentinels(), "persisted attempts");
                if (suffix.equals("rejected") || tokenError) {
                    var replay = JSON.createObjectNode().put("replayRequestId", "privacy-replay").put("expectedRunNumber", 1).put("reason", "operator verified corrected supplier configuration");
                    var first = f.worker.api.send("POST", "/admin/notifications/" + id + "/replay", replay, OPERATOR_A);
                    assertEquals(200, first.statusCode()); publicResponses.add(first.body());
                    var second = f.worker.api.send("POST", "/admin/notifications/" + id + "/replay", replay, OPERATOR_A);
                    assertEquals(f.worker.api.json(first), f.worker.api.json(second));
                }
                f.evidence("cp08-privacy-" + suffix, id);
            }
            publicResponses.add(f.worker.api.get("/v1/notifications?tenantId=tenant-a", OPERATOR_A).body());
            // Mapping rejection and strict unknown-field errors must not echo supplied secrets or data.
            var malformed = f.worker.api.post(payload("tenant-a", "privacy.notify", "privacy-invalid", eventData.deepCopy().put("privateValue", 42).toString()), ADMIN);
            assertEquals(400, malformed.statusCode()); publicResponses.add(malformed.body());
            var unauthorized = f.worker.api.post(payload("tenant-a", "privacy.notify", "privacy-unauthorized", eventData.toString()), "cp08-private-invalid-internal-credential");
            assertEquals(401, unauthorized.statusCode()); publicResponses.add(unauthorized.body());
            for (String response : publicResponses) assertAbsent(response, sensitiveSentinels(), "public response");
            var auditRecords = auditRecords(f);
            String audit = auditRecords.toString();
            assertAuditDetails(auditRecords, published.path("id").asText(), replacement.path("id").asText(), f.auth.path("id").asText(), ids.get(ids.size() - 1));
            assertAbsent(audit, sensitiveSentinels(), "audit summaries");
            assertTrue(audit.contains("operator-a")); assertTrue(audit.contains("privacy-replay"));
            assertTrue(audit.contains("before_summary")); assertTrue(audit.contains("after_summary"));
            assertEquals(1, replayAuditCount(f), "Repeated replay key must produce one audit record");
            String captured = logs.text();
            assertFalse(logs.messages.isEmpty(), "A detached or empty log capture cannot prove redaction");
            assertAbsent(captured, sensitiveSentinels(), "application logs");
            for (int index = 0; index < ids.size(); index++) {
                String id = ids.get(index).toString(), requestId = requestIds.get(index);
                assertTrue(logs.messages.stream().anyMatch(line -> line.contains("notificationId=" + id) && line.contains("requestId=" + requestId)), "Inbound request must correlate to its durable notification");
                var completions = logs.messages.stream().filter(line -> line.contains("delivery_completion") && line.contains("notificationId=" + id)).toList();
                assertEquals(1, completions.size());
                for (String field : List.of("executionId=", "tenantId=tenant-a", "operation=privacy.notify", "providerId=privacy-provider", "runNumber=1", "attemptNo=1", "phase=", "durationMs=", "errorCode=")) {
                    assertTrue(completions.get(0).contains(field), "Missing safe correlation field " + field);
                }
            }
            assertEquals(1, f.tokens.count("/token"));
            assertEquals(tokenError ? 0 : 2, f.worker.mock.count("/private"));
            if (!tokenError) for (var call : f.worker.mock.journal()) {
                assertEquals("Bearer " + TOKEN, call.firstHeader("Authorization")); assertEquals(HEADER, call.firstHeader("X-Private"));
            }
            SecurityAuthorizationTest.evidence("redaction-" + tokenError, JSON.createObjectNode()
                    .put("capturedLogEvents", logs.messages.size()).put("scannedPublicResponses", publicResponses.size())
                    .put("scannedTasks", ids.size()).put("sensitiveValueMatches", 0).put("tokenCalls", f.tokens.count("/token"))
                    .put("businessCalls", f.worker.mock.count("/private")).put("replayAuditRows", replayAuditCount(f)));
        }
    }

    private static List<String> authenticationSentinels() {
        String encodedSecret = java.net.URLEncoder.encode(System.getenv("CP06_CLIENT_SECRET"), StandardCharsets.UTF_8);
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString((java.net.URLEncoder.encode("client: +中", StandardCharsets.UTF_8)
                + ":" + encodedSecret).getBytes(StandardCharsets.US_ASCII));
        return List.of(TOKEN, System.getenv("CP06_CLIENT_SECRET"), encodedSecret, basic, System.getenv("CP04_TEST_SECRET"), ADMIN, CALLER_A, OPERATOR_A,
                "cp08-private-invalid-internal-credential", RESPONSE, TOKEN_ERROR);
    }
    private static List<String> sensitiveSentinels() {
        var values = new ArrayList<>(authenticationSentinels()); values.add(BODY); values.add(HEADER); return values;
    }
    private static void assertAbsent(String content, List<String> values, String source) {
        for (String value : values) assertFalse(content.contains(value), source + " must not contain sensitive fixture material");
    }
    private static List<com.fasterxml.jackson.databind.JsonNode> auditRecords(OAuthFixture f) throws Exception {
        var result = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try (var connection = f.worker.api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_jsonb(e)::text FROM " + f.worker.api.database.schema() + ".audit_event e")) {
            while (rows.next()) result.add(JSON.readTree(rows.getString(1)));
        }
        return List.copyOf(result);
    }
    private static void assertAuditDetails(List<com.fasterxml.jackson.databind.JsonNode> records, String originalTemplate,
            String replacementTemplate, String auth, UUID replayedTask) throws Exception {
        for (String template : List.of(originalTemplate, replacementTemplate)) {
            var publication = records.stream().filter(row -> row.path("action").asText().equals("PUBLISH")
                    && row.path("object_id").asText().equals(template)).toList();
            assertEquals(1, publication.size());
            var entry = publication.get(0);
            assertEquals("admin", entry.path("actor_id").asText()); assertEquals("tenant-a", entry.path("tenant_id").asText());
            assertEquals(JSON.readTree("{\"revision\":1,\"status\":\"draft\"}"), entry.path("before_summary"));
            assertEquals(JSON.readTree("{\"revision\":2,\"status\":\"published\"}"), entry.path("after_summary"));
        }
        var bindings = records.stream().filter(row -> row.path("action").asText().equals("BIND")
                && row.path("object_id").asText().equals("tenant-a:privacy.notify"))
                .sorted(java.util.Comparator.comparingInt(row -> row.path("after_summary").path("revision").asInt())).toList();
        assertEquals(3, bindings.size());
        com.fasterxml.jackson.databind.JsonNode previous = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        var templateVersions = List.of(originalTemplate, replacementTemplate, originalTemplate);
        for (int i = 0; i < bindings.size(); i++) {
            var expected = JSON.readTree(JSON.createObjectNode().put("revision", i + 1).put("templateVersionId", templateVersions.get(i)).put("authProfileId", auth).toString());
            assertEquals(previous, bindings.get(i).path("before_summary"));
            assertEquals(expected, bindings.get(i).path("after_summary"));
            assertEquals("admin", bindings.get(i).path("actor_id").asText());
            previous = expected;
        }
        var replays = records.stream().filter(row -> row.path("action").asText().equals("REPLAY")).toList();
        assertEquals(1, replays.size()); var replay = replays.get(0);
        assertEquals(replayedTask.toString(), replay.path("object_id").asText());
        assertEquals("operator-a", replay.path("actor_id").asText()); assertEquals("tenant-a", replay.path("tenant_id").asText());
        assertEquals("privacy-replay", replay.path("request_key").asText());
        assertEquals("operator verified corrected supplier configuration", replay.path("reason").asText());
        assertEquals(JSON.readTree("{\"runNumber\":1,\"status\":\"failed\"}"), replay.path("before_summary"));
        assertEquals(JSON.readTree("{\"runNumber\":2,\"status\":\"pending\"}"), replay.path("after_summary"));
    }

    private static int replayAuditCount(OAuthFixture f) throws Exception {
        try (var connection = f.worker.api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + f.worker.api.database.schema() + ".audit_event WHERE action='REPLAY'")) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
    static final class CapturedLogs extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        CapturedLogs() { setContext(logger.getLoggerContext()); start(); logger.addAppender(this); }
        @Override protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())));
            if (messages.size() > 10000) throw new AssertionError("Unexpectedly unbounded test log capture");
        }
        String text() { assertTrue(isStarted() && logger.isAttached(this), "Log capture must remain attached to the actual running LoggerContext"); return String.join("\n", messages); }
        @Override public void close() { logger.detachAppender(this); stop(); }
    }
}
