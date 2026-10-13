package com.rc.notification.security;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.ConfigurationFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP08 TC-SEC-07/08, TC-REPLAY-04, TC-OBS-04: real authorization at every API family, no test-only endpoints. */
@Timeout(60)
class SecurityAuthorizationTest {
    @Test
    void roleAndTenantMatrixProtectsEveryManagementOperationAndCreatesNoFalseAudit() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var templateInput = scalarTemplate("tenant-a", "private-template");
            var authInput = apiKey("tenant-a");
            var template = config.createTemplate(templateInput);
            var auth = config.createAuth(authInput);
            var installed = config.install(scalarTemplate("tenant-a", "published"), apiKey("tenant-a"), "security.notify");
            String templatePath = "/admin/template-versions/" + template.path("id").asText();
            String authPath = "/admin/auth-profiles/" + auth.path("id").asText();
            var operations = List.of(
                    new Call("POST", "/admin/template-versions", scalarTemplate("tenant-a", "unauthorized")),
                    new Call("GET", "/admin/template-versions?tenantId=tenant-a", null), new Call("GET", templatePath, null),
                    new Call("PUT", templatePath, templateInput.deepCopy().put("expectedRevision", 1)),
                    new Call("POST", templatePath + "/validate", JSON.createObjectNode()),
                    new Call("POST", templatePath + "/preview", JSON.createObjectNode().put("eventId", "preview").set("data", JSON.createObjectNode())),
                    new Call("POST", templatePath + "/publish", JSON.createObjectNode().put("expectedRevision", 1)),
                    new Call("POST", "/admin/auth-profiles", apiKey("tenant-a")),
                    new Call("GET", "/admin/auth-profiles?tenantId=tenant-a", null), new Call("GET", authPath, null),
                    new Call("PUT", authPath, authInput.deepCopy().put("expectedRevision", 1)),
                    new Call("POST", authPath + "/publish", JSON.createObjectNode().put("expectedRevision", 1)),
                    new Call("GET", "/admin/routes?tenantId=tenant-a", null), new Call("GET", "/admin/routes/tenant-a/security.notify", null),
                    new Call("PUT", "/admin/routes/tenant-a/security.notify", routeBody(installed.template(), installed.auth(), 1)));
            long audits = count(api, "audit_event");
            int rejected = 0;
            for (var operation : operations) {
                assertEquals(401, operation.execute(api, null).statusCode(), operation.path()); rejected++;
                for (String caller : List.of(CALLER_A, OPERATOR_A)) {
                    assertEquals(403, operation.execute(api, caller).statusCode(), operation.path()); rejected++;
                }
            }
            for (var operation : operations) {
                int expected = operation.path().equals("/admin/template-versions") || operation.path().equals("/admin/auth-profiles")
                        || operation.path().contains("?tenantId=") || operation.method().equals("PUT") && operation.path().contains("/routes/") ? 403 : 404;
                assertEquals(expected, operation.execute(api, CONFIG_B).statusCode(), operation.path()); rejected++;
            }
            assertEquals(audits, count(api, "audit_event"), "Forbidden writes must never create an audit that implies success");
            assertEquals(template, api.json(api.get(templatePath, CONFIG_A)));
            assertEquals(auth, api.json(api.get(authPath, CONFIG_A)));
            assertEquals(1, api.json(api.get("/admin/routes/tenant-a/security.notify", CONFIG_A)).path("revision").asInt());
            // Administrator remains a real, separately authenticated identity; payload fields cannot grant that role.
            var forged = templateInput.deepCopy().put("templateKey", "forged-role").put("roles", "ADMIN").put("callerId", "admin");
            assertEquals(403, api.send("POST", "/admin/template-versions", forged, CALLER_A).statusCode());
            for (String resource : List.of("template-versions", "auth-profiles", "routes")) {
                var page = api.json(api.get("/admin/" + resource, CONFIG_B));
                for (JsonNode item : page.path("items")) assertNotEquals("tenant-a", item.path(resource.equals("template-versions") ? "ownerTenantId" : "tenantId").asText());
            }
            evidence("management-role-matrix", JSON.createObjectNode().put("rejectedRequests", rejected).put("auditBefore", audits).put("auditAfter", count(api, "audit_event")));
        }
    }

    @Test
    void operationScopeAlsoProtectsAttemptsReplayRoutesAndTheirCollections() throws Exception {
        Map<String, Object> properties = Map.of(
                "app.security.callers[8].id", "limited-operator", "app.security.callers[8].token", "cp08-limited-operator",
                "app.security.callers[8].roles", "OPERATOR", "app.security.callers[8].tenants", "tenant-a", "app.security.callers[8].operations", "order.paid",
                "app.security.callers[9].id", "limited-config", "app.security.callers[9].token", "cp08-limited-config",
                "app.security.callers[9].roles", "CONFIG_ADMIN", "app.security.callers[9].tenants", "tenant-a", "app.security.callers[9].operations", "order.paid");
        try (var f = new WorkerFixture(properties)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 403, "private-vendor-response"));
            var id = f.submit("security-scope", "{}"); f.worker.tick(); f.awaitIdle(Duration.ofSeconds(5));
            assertEquals("failed", f.task(id).path("status").asText());
            var before = f.task(id);
            var replay = JSON.createObjectNode().put("replayRequestId", "cp08-role-matrix").put("expectedRunNumber", 1).put("reason", "authorized recovery");
            for (String caller : List.of(OPERATOR_B, "cp08-limited-operator")) {
                assertEquals(404, f.api.get("/v1/notifications/" + id, caller).statusCode());
                assertEquals(404, f.api.get("/v1/notifications/" + id + "/attempts", caller).statusCode());
                assertEquals(404, f.api.send("POST", "/admin/notifications/" + id + "/replay", replay, caller).statusCode());
            }
            for (String caller : List.of(CALLER_A, CONFIG_A)) assertEquals(403,
                    f.api.send("POST", "/admin/notifications/" + id + "/replay", replay, caller).statusCode());
            assertEquals(before, f.task(id)); assertEquals(0, count(f.api, "replay_request"));
            var route = f.api.json(f.api.get("/admin/routes/tenant-a/worker.notify", ADMIN));
            assertEquals(404, f.api.get("/admin/routes/tenant-a/worker.notify", "cp08-limited-config").statusCode());
            var routeUpdate = JSON.createObjectNode().put("expectedRevision", route.path("revision").asInt())
                    .put("templateVersionId", route.path("templateVersionId").asText()).put("authProfileId", route.path("authProfileId").asText());
            assertEquals(403, f.api.send("PUT", "/admin/routes/tenant-a/worker.notify", routeUpdate, "cp08-limited-config").statusCode());
            for (JsonNode item : f.api.json(f.api.get("/admin/routes?tenantId=tenant-a", "cp08-limited-config")).path("items")) assertEquals("order.paid", item.path("operation").asText());
            assertEquals(0, f.api.json(f.api.get("/v1/notifications?tenantId=tenant-a", "cp08-limited-operator")).path("items").size());
            assertEquals(200, f.api.get("/v1/notifications/" + id + "/attempts", OPERATOR_A).statusCode());
            assertEquals(200, f.api.send("POST", "/admin/notifications/" + id + "/replay", replay, OPERATOR_A).statusCode());
            assertEquals(1, count(f.api, "replay_request"));
            f.evidence("cp08-authorized-replay-after-scope-rejections", id, null);
        }
    }

    @Test
    void tenantConfigCannotBorrowAnotherTenantsSecretReferenceOrBypassItWithAdminRole() throws Exception {
        try (var f = new WorkerFixture(Map.of("app.secrets.tenant-references[tenant-b]", "env:CP06_ROTATED_SECRET",
                "app.secrets.tenant-references[tenant-a]", "env:CP04_TEST_SECRET"))) {
            var foreign = apiKey("tenant-b").put("secretRef", "env:CP06_ROTATED_SECRET");
            var foreignAuth = f.configuration.create("auth-profiles", foreign, CONFIG_B);
            f.configuration.publish("auth-profiles", foreignAuth);
            var ownInput = apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET");
            var own = f.configuration.create("auth-profiles", ownInput, CONFIG_A);
            long auths = count(f.api, "auth_profile"), audits = count(f.api, "audit_event");
            for (String caller : List.of(CONFIG_A, ADMIN)) {
                var attempted = apiKey("tenant-a").put("secretRef", "env:CP06_ROTATED_SECRET");
                var denied = f.api.send("POST", "/admin/auth-profiles", attempted, caller);
                assertEquals(403, denied.statusCode()); assertEquals("SECRET_REFERENCE_FORBIDDEN", f.api.json(denied).path("code").asText());
                assertFalse(denied.body().contains("CP06_ROTATED_SECRET"));
                var update = ownInput.deepCopy().put("expectedRevision", 1).put("secretRef", "env:CP06_ROTATED_SECRET");
                assertEquals(403, f.api.send("PUT", "/admin/auth-profiles/" + own.path("id").asText(), update, caller).statusCode());
            }
            assertEquals(auths, count(f.api, "auth_profile")); assertEquals(audits, count(f.api, "audit_event"));
            assertEquals(own, f.api.json(f.api.get("/admin/auth-profiles/" + own.path("id").asText(), CONFIG_A)));
            assertEquals(0, f.mock.journal().size());
            evidence("tenant-secret-permission", JSON.createObjectNode().put("rejectedRequests", 4).put("authRowsUnchanged", true).put("auditRowsUnchanged", true).put("businessCalls", 0));
        }
    }

    @Test
    void deploymentSecretRevocationAppliesToPublicationAndPreviouslyAcceptedSnapshots() throws Exception {
        try (var f = new WorkerFixture()) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 200, "must-not-be-sent"));
            var draft = f.configuration.createAuth(apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET"));
            var taskId = f.submit("secret-revoked-after-acceptance", "{}");
            var before = f.task(taskId);
            f.restart(Map.of("app.secrets.tenant-references[tenant-a]", "env:CP03_NEVER_RESOLVED_SECRET"));
            var publish = f.api.send("POST", "/admin/auth-profiles/" + draft.path("id").asText() + "/publish",
                    JSON.createObjectNode().put("expectedRevision", 1), CONFIG_A);
            assertEquals(403, publish.statusCode()); assertEquals("SECRET_REFERENCE_FORBIDDEN", f.api.json(publish).path("code").asText());
            assertEquals("draft", f.api.json(f.api.get("/admin/auth-profiles/" + draft.path("id").asText(), CONFIG_A)).path("status").asText());
            assertEquals(1, f.worker.tick()); f.awaitIdle(Duration.ofSeconds(5));
            var after = f.task(taskId);
            assertEquals(before.path("body_bytes"), after.path("body_bytes"));
            assertEquals("failed", after.path("status").asText()); assertEquals("NOT_SENT", after.path("last_outcome").asText());
            assertEquals("AUTH_CONFIGURATION_ERROR", after.path("last_error_code").asText());
            assertEquals(1, after.path("run_attempt_count").asInt()); assertEquals(0, f.mock.journal().size());
            f.evidence("cp08-secret-revoked-old-snapshot", taskId, 0);
        }
    }

    @Test
    void revokedOAuthSecretNeverStartsEitherTokenOrBusinessHttp() throws Exception {
        try (var f = new com.rc.notification.support.OAuthFixture(Map.of(), "client_secret_post")) {
            f.tokens.route("/token", e -> HttpMock.respond(e, 200, com.rc.notification.support.OAuthFixture.response("must-not-obtain", 300)));
            f.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "must-not-send"));
            var taskId = f.submit("oauth-permission-revoked");
            f.worker.restart(Map.of("app.secrets.tenant-references[tenant-a]", "env:CP04_TEST_SECRET"));
            assertEquals(1, f.worker.worker.tick()); f.worker.awaitIdle(Duration.ofSeconds(5));
            var state = f.worker.task(taskId);
            assertEquals("failed", state.path("status").asText()); assertEquals("NOT_SENT", state.path("last_outcome").asText());
            assertEquals("AUTH_CONFIGURATION_ERROR", state.path("last_error_code").asText());
            assertEquals(1, state.path("run_attempt_count").asInt());
            assertEquals(0, f.tokens.journal().size()); assertEquals(0, f.worker.mock.journal().size());
            f.evidence("cp08-oauth-secret-revoked", taskId);
        }
    }

    @Test
    void productionPublicationRejectsUnsafeBusinessAndTokenUrlsWithoutConnecting() throws Exception {
        try (var peer = new com.rc.notification.support.RawHttpPeer(socket -> { });
                var api = new NotificationApiFixture(new com.rc.notification.support.RealPostgres(), "api", Map.of(
                        "app.outbound.allowed-hosts", "supplier.invalid,127.0.0.1,::1",
                        "app.outbound.allowed-ports", "443," + peer.uri("https", "/").getPort()));
                var logs = new SensitiveDataExposureTest.CapturedLogs()) {
            var config = new ConfigurationFixture(api);
            var urls = List.of(peer.uri("https", "/").toString(), peer.uri("http", "/").toString(),
                    "https://[::1]/notify", "https://127.0.0.1/notify", "https://supplier.invalid:444/notify",
                    "https://forbidden.invalid/notify", "https://cp08-user:cp08-url-private-sentinel@supplier.invalid/notify",
                    "https://supplier.invalid/notify#private-fragment", "http://supplier.invalid/notify", "file:///etc/passwd");
            for (String url : urls) {
                var template = config.createTemplate(scalarTemplate("tenant-a", "safe-marker").put("url", url));
                var auth = config.createAuth(oauth("tenant-a", url));
                for (var resource : List.of(Map.entry("template-versions", template), Map.entry("auth-profiles", auth))) {
                    String path = "/admin/" + resource.getKey() + "/" + resource.getValue().path("id").asText();
                    var rejected = api.send("POST", path + "/publish", JSON.createObjectNode().put("expectedRevision", 1), CONFIG_A);
                    assertEquals(400, rejected.statusCode());
                    assertFalse(rejected.body().contains("cp08-url-private-sentinel"));
                    assertEquals("draft", api.json(api.get(path, CONFIG_A)).path("status").asText());
                }
            }
            assertEquals(0, peer.accepted.get()); assertEquals(0, api.taskCount());
            assertFalse(logs.text().contains("cp08-url-private-sentinel"), "Forbidden URL credentials must not enter application logs");
            evidence("offline-url-publication", JSON.createObjectNode().put("rejectedPublications", urls.size() * 2)
                    .put("observedConnections", peer.accepted.get()).put("taskRows", api.taskCount())
                    .put("capturedLogEvents", logs.messages.size()).put("sensitiveUrlMatches", 0));
        }
    }

    @Test
    void productionApiExposesNoFaultSwitchOrScriptExecutionSurface() throws Exception {
        try (var api = new NotificationApiFixture()) {
            for (String path : List.of("/test/faults", "/admin/faults", "/debug/worker", "/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/admin/audit-events")) {
                assertEquals(404, api.get(path, ADMIN).statusCode(), path);
            }
            var request = (ObjectNode) JSON.readTree(payload("tenant-a", "user.registered", "test-control", "{}"));
            request.put("app.environment", "test").put("allowPrivateNetwork", true).put("faultPhase", "AFTER_HTTP");
            assertEquals(400, api.post(request.toString(), ADMIN).statusCode());
            assertEquals(0, api.taskCount());
            assertEquals("production", api.application.getEnvironment().getProperty("app.environment", "production"));
        }
    }

    static long count(NotificationApiFixture api, String table) throws Exception {
        try (var connection = api.database.connection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + api.database.schema() + "." + table)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    static void evidence(String name, ObjectNode document) throws Exception {
        document.put("scenario", "cp08-" + name).put("observedAt", java.time.Instant.now().toString());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/evidence"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/evidence/cp08-" + name + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }
    private record Call(String method, String path, JsonNode body) {
        java.net.http.HttpResponse<String> execute(NotificationApiFixture api, String caller) throws Exception {
            return method.equals("GET") ? api.get(path, caller) : api.send(method, path, body, caller);
        }
    }
}
