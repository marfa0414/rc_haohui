package com.rc.notification.config;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP03 TC-CFG-07/09: tenant-scoped management, shared templates, namespace ownership and pagination. */
@Timeout(90)
class ConfigurationAccessTest {
    @Test
    void sharedTemplatesDoNotShareCredentialAuthorityOrMoveOtherTenantBindings() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var sharedInput = scalarTemplate("tenant-a", "shared-v1").put("shared", true);
            var shared = config.publish("template-versions", config.createTemplate(sharedInput));
            var authA = config.publish("auth-profiles", config.createAuth(apiKey("tenant-a")));
            var authB = config.publish("auth-profiles", config.createAuth(apiKey("tenant-b")));
            var routeA = config.bind("tenant-a", "shared.operation", shared, authA, 0);
            config.bind("tenant-b", "shared.operation", shared, authB, 0);
            assertEquals(200, api.get("/admin/template-versions/" + shared.path("id").asText(), CONFIG_B).statusCode());
            assertEquals(404, api.get("/admin/auth-profiles/" + authB.path("id").asText(), CONFIG_A).statusCode());
            assertEquals(404, api.send("PUT", "/admin/template-versions/" + shared.path("id").asText(),
                    sharedInput.deepCopy().put("expectedRevision", shared.path("revision").asLong()), CONFIG_A).statusCode());
            var forged = api.send("PUT", "/admin/routes/tenant-a/shared.operation", routeBody(shared, authB, routeA.path("revision").asLong()), CONFIG_A);
            assertEquals(403, forged.statusCode(), forged.body());
            JsonNode unchanged = api.json(api.get("/admin/routes/tenant-a/shared.operation", CONFIG_A));
            assertEquals(authA.path("id"), unchanged.path("authProfileId"));
            assertEquals(routeA.path("revision"), unchanged.path("revision"));
            var secondInput = sharedInput.deepCopy().put("version", 2);
            ((com.fasterxml.jackson.databind.node.ObjectNode) secondInput.path("body").path("fields").path("marker")).put("constant", "shared-v2");
            var second = config.publish("template-versions", config.createTemplate(secondInput));
            config.bind("tenant-a", "shared.operation", second, authA, routeA.path("revision").asLong());
            assertEquals(shared.path("id"), api.json(api.get("/admin/routes/tenant-b/shared.operation", CONFIG_B)).path("templateVersionId"));
            assertEquals(403, api.send("POST", "/admin/template-versions", scalarTemplate("tenant-a", "unauthorized-business"), CALLER_A).statusCode());
            assertEquals(403, api.send("POST", "/admin/template-versions", scalarTemplate("tenant-b", "forged-owner"), CONFIG_A).statusCode());
            assertEquals(404, api.get("/admin/routes/tenant-b/shared.operation", CONFIG_A).statusCode());
            assertThrows(SQLException.class, () -> api.sql("UPDATE notification_route SET template_version_id='" + UUID.randomUUID()
                    + "' WHERE tenant_id='tenant-a' AND operation='shared.operation'"));
            assertEquals(404, api.send("PUT", "/admin/routes/tenant-a/missing.reference",
                    JSON.createObjectNode().put("expectedRevision", 0).put("templateVersionId", UUID.randomUUID().toString())
                            .put("authProfileId", authA.path("id").asText()), CONFIG_A).statusCode());
        }
    }

    @Test
    void concurrentDifferentTenantsCannotCaptureDifferentVersionsOfOneGlobalTemplateKey() throws Exception {
        try (var api = new NotificationApiFixture()) {
            String key = "namespace-" + UUID.randomUUID();
            var inputA = scalarTemplate("tenant-a", "a").put("templateKey", key).put("version", 1);
            var inputB = scalarTemplate("tenant-b", "b").put("templateKey", key).put("version", 2);
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("POST", "/admin/template-versions", inputA, CONFIG_A); });
                var b = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("POST", "/admin/template-versions", inputB, CONFIG_B); });
                start.countDown();
                var responses = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
                assertEquals(List.of(201, 404), responses.stream().map(java.net.http.HttpResponse::statusCode).sorted().toList());
                assertEquals(1, ConfigurationLifecycleTest.count(api, "notification_template_version", "template_key='" + key + "'"));
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }

    @Test
    void maximumTenantAndOperationIdentifiersFitTheRouteAndItsAuditRecord() throws Exception {
        String tenant = "t".repeat(64), operation = "o".repeat(128);
        try (var api = new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                "app.secrets.tenant-references[" + tenant + "]", "env:CP03_NEVER_RESOLVED_SECRET"))) {
            var config = new ConfigurationFixture(api);
            var installed = config.install(scalarTemplate(tenant, "max-identifiers"), apiKey(tenant), operation);
            assertEquals(1, installed.route().path("revision").asLong());
            var submitted = api.post(payload(tenant, operation, "maximum-route-event", "{}"), ADMIN);
            assertEquals(202, submitted.statusCode(), submitted.body());
            assertTrue(ConfigurationLifecycleTest.count(api, "audit_event", "length(object_id)>128") > 0,
                    "The accepted long route must have a complete audit identity");
        }
    }

    @Test
    void allManagementCollectionsPaginateWithoutRepeatsAndHonorTenantScope() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var template = config.createTemplate(scalarTemplate("tenant-a", "list-seed"));
            var auth = config.createAuth(apiKey("tenant-a"));
            api.sql("INSERT INTO notification_template_version SELECT clone.* FROM notification_template_version seed "
                    + "CROSS JOIN generate_series(1,115) seq CROSS JOIN LATERAL jsonb_populate_record(NULL::notification_template_version,"
                    + "to_jsonb(seed)||jsonb_build_object('id',gen_random_uuid(),'template_key',seed.template_key||'-fixture-'||seq,"
                    + "'created_at','2026-01-01T00:00:00Z')) clone WHERE seed.id='" + template.path("id").asText() + "'");
            api.sql("INSERT INTO auth_profile SELECT clone.* FROM auth_profile seed CROSS JOIN generate_series(1,115) seq "
                    + "CROSS JOIN LATERAL jsonb_populate_record(NULL::auth_profile,to_jsonb(seed)||jsonb_build_object('id',gen_random_uuid(),"
                    + "'profile_key',seed.profile_key||'-fixture-'||seq,'created_at','2026-01-01T00:00:00Z')) clone WHERE seed.id='"
                    + auth.path("id").asText() + "'");
            api.sql("INSERT INTO notification_route SELECT clone.* FROM notification_route seed CROSS JOIN generate_series(1,115) seq "
                    + "CROSS JOIN LATERAL jsonb_populate_record(NULL::notification_route,to_jsonb(seed)||jsonb_build_object('operation','paged.'||lpad(seq::text,3,'0'))) clone "
                    + "WHERE seed.tenant_id='tenant-a' AND seed.operation='user.registered'");
            for (String resource : List.of("template-versions", "auth-profiles", "routes")) {
                var defaultPage = api.get("/admin/" + resource + "?tenantId=tenant-a", CONFIG_A);
                assertEquals(200, defaultPage.statusCode(), defaultPage.body());
                assertEquals(20, api.json(defaultPage).path("items").size());
                var ids = readPages(api, resource);
                assertEquals(ids.size(), new HashSet<>(ids).size());
                String table = switch (resource) { case "template-versions" -> "notification_template_version"; case "auth-profiles" -> "auth_profile"; default -> "notification_route"; };
                String tenantColumn = resource.equals("template-versions") ? "owner_tenant_id" : "tenant_id";
                String idColumn = resource.equals("routes") ? "operation" : "id::text";
                String order = resource.equals("routes") ? "tenant_id,operation" : "created_at DESC,id DESC";
                List<String> expected = new ArrayList<>();
                try (var connection = api.database.connection(); var query = connection.createStatement();
                        var rows = query.executeQuery("SELECT " + idColumn + " FROM " + api.database.schema() + "." + table
                                + " WHERE " + tenantColumn + "='tenant-a' ORDER BY " + order)) {
                    while (rows.next()) expected.add(rows.getString(1));
                }
                assertEquals(expected, ids, resource);
                assertEquals(403, api.get("/admin/" + resource + "?tenantId=tenant-b", CONFIG_A).statusCode());
                assertEquals(400, api.get("/admin/" + resource + "?tenantId=tenant-a&limit=101", CONFIG_A).statusCode());
                assertEquals(400, api.get("/admin/" + resource + "?tenantId=tenant-a&cursor=invalid", CONFIG_A).statusCode());
            }
        }
    }

    private static List<String> readPages(NotificationApiFixture api, String resource) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            String path = "/admin/" + resource + "?tenantId=tenant-a&limit=100";
            if (cursor != null) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            var response = api.get(path, CONFIG_A);
            assertEquals(200, response.statusCode(), response.body());
            var body = api.json(response);
            assertTrue(body.path("items").size() <= 100);
            for (var item : body.path("items")) {
                assertEquals("tenant-a", item.path(resource.equals("template-versions") ? "ownerTenantId" : "tenantId").asText());
                ids.add(item.path(resource.equals("routes") ? "operation" : "id").asText());
            }
            JsonNode next = body.path("nextCursor");
            if (next.isNull() || next.isMissingNode()) return ids;
            cursor = next.asText();
            assertFalse(cursor.isBlank());
        }
        fail("Management pagination did not terminate");
        return ids;
    }
}
