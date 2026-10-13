package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP05 TC-OBS-01 and attempt-history portion of TC-SEC-07: query the actual committed attempt history. */
@Timeout(90)
class AttemptHistoryTest {
    @Test
    void historyHasStableKeysetPagesAcrossRunsWithNoSensitivePayloadOrAuthorizationLeak() throws Exception {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.put("app.delivery.max-attempts", "105");
        // CP07: retain 105 real failed attempts while testing pagination, independently of circuit opening.
        properties.put("app.protection.sliding-window-size", "200");
        properties.put("app.protection.minimum-calls", "200");
        try (var f = new WorkerFixture(properties)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 500, "supplier-sensitive-response-sentinel"));
            var id = f.submit("history-pagination", "{\"payload\":{\"secret\":\"body-sensitive-sentinel\"}}");
            for (int attempt = 1; attempt <= 105; attempt++) executeNextAttempt(f, id);
            assertEquals("failed", f.task(id).path("status").asText());
            String base = "/v1/notifications/" + id + "/attempts";
            var first = f.api.get(base, OPERATOR_A);
            assertEquals(200, first.statusCode(), first.body());
            var page = f.api.json(first);
            assertEquals(20, page.path("items").size());
            var captured = new ArrayList<JsonNode>(); page.path("items").forEach(captured::add);
            assertEquals(105, captured.get(0).path("attemptNo").asInt());
            assertEquals(200, ReplayTest.replay(f, id, ReplayTest.body("history-new-round", 1, "history-check"), OPERATOR_A).statusCode());
            executeNextAttempt(f, id); // A newly inserted higher run must not cause duplicates in older cursor pages.
            String cursor = page.path("nextCursor").asText(null);
            while (cursor != null) {
                var response = f.api.get(base + "?limit=7&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8), OPERATOR_A);
                assertEquals(200, response.statusCode(), response.body());
                page = f.api.json(response); page.path("items").forEach(captured::add);
                cursor = page.path("nextCursor").asText(null);
                assertTrue(captured.size() <= 105, "Pagination must terminate without repeats");
            }
            assertEquals(105, captured.size());
            var unique = new HashSet<String>();
            for (int index = 0; index < captured.size(); index++) {
                var record = captured.get(index);
                assertEquals(1, record.path("runNumber").asInt());
                assertEquals(105 - index, record.path("attemptNo").asInt());
                assertTrue(unique.add(record.path("runNumber") + ":" + record.path("attemptNo")));
                String raw = record.toString();
                for (String sentinel : new String[]{"supplier-sensitive-response-sentinel", "body-sensitive-sentinel", "cp04-test-only-secret", ADMIN})
                    assertFalse(raw.contains(sentinel));
            }
            var latest = f.api.json(f.api.get(base + "?limit=100", OPERATOR_A));
            assertEquals(100, latest.path("items").size());
            assertEquals(2, latest.path("items").get(0).path("runNumber").asInt());
            assertEquals(1, latest.path("items").get(0).path("attemptNo").asInt());
            for (String suffix : new String[]{"?limit=0", "?limit=101", "?limit=abc", "?cursor=invalid-cursor"}) {
                assertEquals(400, f.api.get(base + suffix, OPERATOR_A).statusCode(), suffix);
            }
            assertEquals(404, f.api.get(base, OPERATOR_B).statusCode());
            assertEquals(404, f.api.get(base, LIMITED).statusCode());
            assertEquals(404, f.api.get(base, CONFIG_A).statusCode(), "Single-resource reads hide unauthorized task existence");
            assertEquals(401, f.api.get(base, null).statusCode());
            assertEquals(404, f.api.get("/v1/notifications/" + UUID.randomUUID() + "/attempts", OPERATOR_A).statusCode());
            f.evidence("cp05-attempt-history", id, null);
        }
    }
}
