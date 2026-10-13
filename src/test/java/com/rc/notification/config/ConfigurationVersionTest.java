package com.rc.notification.config;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.NotificationApiFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP03 TC-CFG-04/05/06 and TC-IDEM-05: atomic bindings, immutable snapshots and dedup before new mapping. */
@Timeout(90)
class ConfigurationVersionTest {
    @Test
    void routeCompareAndSetHasOneWinnerAndNeverMixesTheTwoVersionReferences() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            var first = config.install(scalarTemplate("tenant-a", "v1"), apiKey("tenant-a"), "route.cas");
            var secondTemplate = config.publish("template-versions", config.createTemplate(scalarTemplate("tenant-a", "v2")));
            var secondAuth = config.publish("auth-profiles", config.createAuth(apiKey("tenant-a")));
            long revision = first.route().path("revision").asLong();
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var one = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("PUT", "/admin/routes/tenant-a/route.cas", routeBody(first.template(), first.auth(), revision), ADMIN); });
                var two = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return api.send("PUT", "/admin/routes/tenant-a/route.cas", routeBody(secondTemplate, secondAuth, revision), ADMIN); });
                start.countDown();
                var responses = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
                assertEquals(List.of(200, 409), responses.stream().map(java.net.http.HttpResponse::statusCode).sorted().toList());
                JsonNode winner = api.json(responses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow());
                JsonNode route = api.json(api.get("/admin/routes/tenant-a/route.cas", ADMIN));
                assertEquals(winner.path("templateVersionId"), route.path("templateVersionId"));
                assertEquals(winner.path("authProfileId"), route.path("authProfileId"));
                assertEquals(revision + 1, route.path("revision").asLong());
                assertEquals(409, api.send("PUT", "/admin/routes/tenant-a/route.cas", routeBody(first.template(), first.auth(), 0), ADMIN).statusCode());
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }

    @Test
    void submissionsDuringRepeatedRouteChangesSnapshotAWholeVersion() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            ObjectNode inputOne = markedTemplate("v1", "https://supplier.invalid/v1");
            var first = config.install(inputOne, apiKey("tenant-a"), "route.switch");
            ObjectNode inputTwo = markedTemplate("v2", "https://supplier-two.invalid/v2");
            inputTwo.put("templateKey", inputOne.path("templateKey").asText()).put("version", 2);
            var secondTemplate = config.publish("template-versions", config.createTemplate(inputTwo));
            var secondAuth = config.publish("auth-profiles", config.createAuth(apiKey("tenant-a")));
            var pool = Executors.newFixedThreadPool(3);
            var start = new CountDownLatch(1);
            try {
                var switches = pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    long revision = first.route().path("revision").asLong();
                    for (int index = 0; index < 20; index++) {
                        boolean second = index % 2 == 0;
                        var result = config.bind("tenant-a", "route.switch", second ? secondTemplate : first.template(),
                                second ? secondAuth : first.auth(), revision);
                        revision = result.path("revision").asLong();
                    }
                    return revision;
                });
                List<java.util.concurrent.Future<List<String>>> submitters = new ArrayList<>();
                for (int caller = 0; caller < 2; caller++) {
                    int number = caller;
                    submitters.add(pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        List<String> ids = new ArrayList<>();
                        for (int index = 0; index < 25; index++) {
                            var result = api.post(payload("tenant-a", "route.switch", "switch-" + number + "-" + index, "{}"), ADMIN);
                            assertEquals(202, result.statusCode(), result.body());
                            ids.add(api.json(result).path("notificationId").asText());
                        }
                        return ids;
                    }));
                }
                start.countDown();
                switches.get(30, TimeUnit.SECONDS);
                List<String> ids = new ArrayList<>();
                for (var submitter : submitters) ids.addAll(submitter.get(30, TimeUnit.SECONDS));
                assertEquals(50, ids.size());
                try (var connection = api.database.connection(); var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT template_version_id,auth_profile_id,request_url,ordinary_headers::text,body_bytes FROM "
                                + api.database.schema() + ".notification_task WHERE operation='route.switch'")) {
                    int count = 0;
                    while (rows.next()) {
                        boolean v1 = rows.getString(1).equals(first.template().path("id").asText());
                        assertEquals(v1 ? first.template().path("id").asText() : secondTemplate.path("id").asText(), rows.getString(1));
                        assertEquals(v1 ? first.auth().path("id").asText() : secondAuth.path("id").asText(), rows.getString(2));
                        assertEquals(v1 ? "https://supplier.invalid/v1" : "https://supplier-two.invalid/v2", rows.getString(3));
                        assertEquals(v1 ? "v1" : "v2", JSON.readTree(rows.getString(4)).path("X-Version").asText());
                        assertEquals(v1 ? "v1" : "v2", JSON.readTree(rows.getBytes(5)).path("marker").asText());
                        count++;
                    }
                    assertEquals(50, count);
                }
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }

    @Test
    void oldEventSurvivesNewRequiredFieldsAndRollbackOnlyChangesFutureTasks() throws Exception {
        try (var api = new NotificationApiFixture()) {
            var config = new ConfigurationFixture(api);
            ObjectNode firstInput = scalarTemplate("tenant-a", "v1");
            var first = config.install(firstInput, apiKey("tenant-a"), "route.rollback");
            String originalEvent = payload("tenant-a", "route.rollback", "old-event", "{}");
            var oldTask = api.post(originalEvent, ADMIN);
            assertEquals(202, oldTask.statusCode(), oldTask.body());
            String oldId = api.json(oldTask).path("notificationId").asText();
            byte[] oldBytes = config.bodyBytes(oldId);
            ObjectNode newInput = template("tenant-a", "json", JSON.readTree("{\"required\":{\"from\":\"data.needed\",\"type\":\"string\",\"required\":true}}"));
            newInput.put("templateKey", firstInput.path("templateKey").asText()).put("version", 2);
            var second = config.publish("template-versions", config.createTemplate(newInput));
            var routeV2 = config.bind("tenant-a", "route.rollback", second, first.auth(), first.route().path("revision").asLong());
            var repeat = api.post(originalEvent, ADMIN);
            assertEquals(200, repeat.statusCode(), repeat.body());
            assertEquals(oldId, api.json(repeat).path("notificationId").asText());
            assertEquals(400, api.post(payload("tenant-a", "route.rollback", "new-missing", "{}"), ADMIN).statusCode());
            var newTask = api.post(payload("tenant-a", "route.rollback", "new-valid", "{\"needed\":\"v2\"}"), ADMIN);
            assertEquals(202, newTask.statusCode(), newTask.body());
            byte[] newBytes = config.bodyBytes(api.json(newTask).path("notificationId").asText());
            config.bind("tenant-a", "route.rollback", first.template(), first.auth(), routeV2.path("revision").asLong());
            var rollbackTask = api.post(payload("tenant-a", "route.rollback", "after-rollback", "{}"), ADMIN);
            assertEquals(202, rollbackTask.statusCode(), rollbackTask.body());
            assertArrayEquals(oldBytes, config.bodyBytes(api.json(rollbackTask).path("notificationId").asText()));
            assertArrayEquals(oldBytes, config.bodyBytes(oldId));
            assertArrayEquals(newBytes, config.bodyBytes(api.json(newTask).path("notificationId").asText()));
            assertEquals(3, api.taskCount());
        }
    }

    private static ObjectNode markedTemplate(String marker, String url) {
        var input = scalarTemplate("tenant-a", marker).put("url", url);
        input.set("headers", JSON.createObjectNode().set("X-Version", JSON.createObjectNode().put("constant", marker)));
        return input;
    }
}
