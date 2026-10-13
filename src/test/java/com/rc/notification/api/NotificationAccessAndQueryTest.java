package com.rc.notification.api;

import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP02 TC-API-01/04/05/06/07; states seeded for query tests do not claim Worker behavior exists. */
@Timeout(60)
class NotificationAccessAndQueryTest {
    @Test
    void acceptsOnlyAfterCommitAndReportsThePendingSnapshotWithoutSecrets() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var response = api.submit("{\"sensitive\":\"sensitive-body-sentinel\"}");
            assertEquals(202, response.statusCode(), response.body());
            var created = api.json(response);
            assertFalse(created.path("deduplicated").asBoolean(true));
            String id = created.path("notificationId").asText();
            UUID.fromString(id);
            try (var connection = api.database.connection(); var statement = connection.prepareStatement(
                    "SELECT status,run_number,run_attempt_count,total_attempt_count,body_bytes,ordinary_headers::text,"
                            + "deadline_at > created_at AS deadline_after_creation,request_hash "
                            + "FROM " + api.database.schema() + ".notification_task WHERE id = ?")) {
                statement.setObject(1, UUID.fromString(id));
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), "An independent connection sees the durable row as soon as 202 is returned");
                    assertEquals("pending", rows.getString("status"));
                    assertEquals(1, rows.getInt("run_number"));
                    assertEquals(0, rows.getInt("run_attempt_count"));
                    assertEquals(0, rows.getLong("total_attempt_count"));
                    assertEquals(JSON.readTree("{\"marker\":\"cp02\"}"), JSON.readTree(rows.getBytes("body_bytes")));
                    assertEquals("cp02", JSON.readTree(rows.getString("ordinary_headers")).path("X-Source").asText());
                    assertTrue(rows.getBoolean("deadline_after_creation"));
                    assertEquals(32, rows.getBytes("request_hash").length);
                }
            }
            var detailResponse = api.get("/v1/notifications/" + id, CALLER_A);
            assertEquals(200, detailResponse.statusCode());
            var detail = api.json(detailResponse);
            for (String field : List.of("notificationId", "tenantId", "operation", "eventId", "status", "runNumber",
                    "runAttemptCount", "totalAttemptCount", "nextAttemptAt", "deadlineAt", "lastOutcome",
                    "lastErrorCode", "lastHttpStatus", "templateVersionId", "authProfileId", "routeRevision", "createdAt", "updatedAt")) {
                assertTrue(detail.has(field), "Missing task detail field: " + field);
            }
            assertEquals("pending", detail.path("status").asText());
            assertEquals("NOT_SENT", detail.path("lastOutcome").asText());
            assertEquals(0, detail.path("runAttemptCount").asInt(-1));
            for (String forbidden : List.of("sensitive-body-sentinel", "body_bytes", "bodyBytes", "ordinaryHeaders",
                    "Authorization", CALLER_A, "CP02_VENDOR_SECRET")) {
                assertFalse(detailResponse.body().contains(forbidden), "Query must not disclose " + forbidden);
            }
        }
    }

    @Test
    void rejectsMissingWrongTenantAndUnauthorizedOperationCredentialsBeforeAccepting() throws Exception {
        try (var api = new NotificationApiFixture()) {
            String payload = payload("tenant-a", "user.registered", "authorization-event", "{}");
            assertError(api, api.post(payload, null), 401, "UNAUTHENTICATED");
            assertError(api, api.post(payload, "wrong-test-token"), 401, "UNAUTHENTICATED");
            assertError(api, api.post(payload, CALLER_B), 403, "FORBIDDEN_OPERATION");
            assertError(api, api.post(payload, LIMITED), 403, "FORBIDDEN_OPERATION");
            assertError(api, api.post(payload("tenant-b", "user.registered", "authorization-event", "{}"), CALLER_A),
                    403, "FORBIDDEN_OPERATION");
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void unknownAndUnauthorizedTaskIdsHaveTheSameNotFoundShape() throws Exception {
        try (var api = new NotificationApiFixture()) {
            String id = api.json(api.submit("{}")).path("notificationId").asText();
            var otherTenant = api.get("/v1/notifications/" + id, CALLER_B);
            var otherOperation = api.get("/v1/notifications/" + id, LIMITED);
            var missing = api.get("/v1/notifications/" + UUID.randomUUID(), CALLER_A);
            assertEquals(404, otherTenant.statusCode());
            assertEquals(404, otherOperation.statusCode());
            assertEquals(404, missing.statusCode());
            assertEquals(api.json(missing).path("code"), api.json(otherTenant).path("code"));
            assertEquals(api.json(missing).path("message"), api.json(otherTenant).path("message"));
            assertEquals(api.json(missing).path("code"), api.json(otherOperation).path("code"));
        }
    }

    @Test
    void missingDraftAndMalformedConfigurationsCannotBeAccepted() throws Exception {
        try (var api = new NotificationApiFixture()) {
            api.route("tenant-a", "draft.template", "draft", "published", "https://supplier.invalid/notify");
            api.route("tenant-a", "draft.auth", "published", "draft", "https://supplier.invalid/notify");
            api.route("tenant-a", "invalid.config", "published", "published", "ftp://supplier.invalid/notify");
            for (String operation : List.of("missing.route", "draft.template", "draft.auth", "invalid.config")) {
                assertError(api, api.post(payload("tenant-a", operation, "unavailable", "{}"), ADMIN),
                        422, "ROUTE_UNAVAILABLE");
            }
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void workerModeDoesNotExposeNotificationSubmission() throws Exception {
        try (var api = new NotificationApiFixture(new RealPostgres(), "worker", Map.of())) {
            assertEquals(404, api.post(payload("tenant-a", "user.registered", "worker-request", "{}"), CALLER_A).statusCode());
            assertEquals(0, api.taskCount());
        }
    }

    @Test
    void paginatesEqualCreationTimesWithoutGapsAndFiltersTenantStatusAndOperation() throws Exception {
        try (var api = new NotificationApiFixture()) {
            for (String tenant : List.of("tenant-a", "tenant-b")) {
                for (String operation : List.of("user.registered", "order.paid")) {
                    var response = api.post(payload(tenant, operation, "list-seed", "{}"), ADMIN);
                    assertEquals(202, response.statusCode(), response.body());
                }
            }
            api.sql("""
                    INSERT INTO notification_task
                    SELECT clone.* FROM notification_task seed CROSS JOIN generate_series(1,115) seq
                    CROSS JOIN LATERAL jsonb_populate_record(NULL::notification_task,
                      to_jsonb(seed) || jsonb_build_object('id', gen_random_uuid(), 'event_id', 'failed-fixture-' || seq,
                        'status', 'failed', 'next_attempt_at', NULL, 'last_outcome', 'UNKNOWN',
                        'last_error_code', 'TEST_QUERY_FIXTURE', 'created_at', '2026-01-01T00:00:00Z')) clone
                    """);
            var defaultPage = api.get("/v1/notifications?tenantId=tenant-a&status=failed", CALLER_A);
            assertEquals(200, defaultPage.statusCode(), defaultPage.body());
            assertEquals(20, api.json(defaultPage).path("items").size());
            List<String> actual = readAllPages(api, CALLER_A);
            assertEquals(230, actual.size());
            assertEquals(actual.size(), new HashSet<>(actual).size(), "Cursor must not repeat same-timestamp rows");
            List<String> expected = new ArrayList<>();
            try (var connection = api.database.connection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM " + api.database.schema()
                            + ".notification_task WHERE tenant_id='tenant-a' AND status='failed' ORDER BY created_at DESC,id DESC")) {
                while (rows.next()) expected.add(rows.getString(1));
            }
            assertEquals(expected, actual);
            assertEquals(115, readAllPages(api, LIMITED).size(), "Operation authorization applies to every list row");
            assertEquals(403, api.get("/v1/notifications?tenantId=tenant-b&status=failed", CALLER_A).statusCode());
            for (String query : List.of("tenantId=tenant-a&limit=0", "tenantId=tenant-a&limit=101",
                    "tenantId=tenant-a&limit=abc", "tenantId=tenant-a&status=unknown", "status=failed",
                    "tenantId=tenant-a&cursor=not-a-cursor", "tenantId=tenant-a&cursor=e30")) {
                assertError(api, api.get("/v1/notifications?" + query, CALLER_A), 400, "INVALID_REQUEST");
            }
        }
    }

    private static List<String> readAllPages(NotificationApiFixture api, String caller) throws Exception {
        List<String> ids = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String path = "/v1/notifications?tenantId=tenant-a&status=failed&limit=100";
            if (cursor != null) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            var response = api.get(path, caller);
            assertEquals(200, response.statusCode(), response.body());
            var body = api.json(response);
            assertTrue(body.path("items").size() <= 100);
            for (var item : body.path("items")) {
                assertEquals("tenant-a", item.path("tenantId").asText());
                assertEquals("failed", item.path("status").asText());
                if (caller.equals(LIMITED)) assertEquals("order.paid", item.path("operation").asText());
                ids.add(item.path("notificationId").asText());
            }
            cursor = body.path("nextCursor").isNull() || body.path("nextCursor").isMissingNode()
                    ? null : body.path("nextCursor").asText();
            if (cursor == null) return ids;
            assertFalse(cursor.isBlank());
            assertTrue(seenCursors.add(cursor), "Cursor must advance");
        }
        fail("Paging did not terminate within the fixture's maximum page count");
        return ids;
    }

    static void assertError(NotificationApiFixture api, java.net.http.HttpResponse<String> response,
            int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        var json = api.json(response);
        assertEquals(code, json.path("code").asText());
        assertEquals(3, json.size());
        assertFalse(json.path("requestId").asText().isBlank());
        assertFalse(json.path("message").asText().isBlank());
    }
}
