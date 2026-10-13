package com.rc.notification.api;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP07 TC-API-08 / TC-GUARD-08: actual HTTP admission rejects unaccepted work and preserves accepted tasks. */
@Timeout(45)
class IngressProtectionTest {
    @Test
    void unauthenticatedCallsDoNotSpendAdmissionTokensAndARejectedNewEventIsNotPersisted() throws Exception {
        try (var api = new NotificationApiFixture(new RealPostgres(), "api", Map.of(
                "app.admission.requests-per-second", "1", "app.admission.burst-capacity", "2"))) {
            String first = payload("tenant-a", "user.registered", "admission-first", "{}");
            for (int n = 0; n < 5; n++) assertEquals(401, api.post(first, "incorrect-test-token").statusCode());
            var accepted = api.post(first, CALLER_A);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var duplicate = api.post(first, CALLER_A);
            assertEquals(200, duplicate.statusCode(), duplicate.body());
            assertEquals(api.json(accepted).path("notificationId"), api.json(duplicate).path("notificationId"));
            var denied = api.post(payload("tenant-a", "user.registered", "admission-second", "{}"), CALLER_A);
            assertEquals(429, denied.statusCode(), denied.body());
            assertEquals("ADMISSION_RATE_LIMITED", api.json(denied).path("code").asText());
            assertTrue(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            assertEquals(1, api.taskCount());
            String id = api.json(accepted).path("notificationId").asText();
            assertEquals(200, api.get("/v1/notifications/" + id, CALLER_A).statusCode(), "Status reads remain available while submission is limited");
            Thread.sleep(1100);
            var recovered = api.post(payload("tenant-a", "user.registered", "admission-second", "{}"), CALLER_A);
            assertEquals(202, recovered.statusCode(), recovered.body()); assertEquals(2, api.taskCount());
        }
    }

    @Test
    void fullBacklogRejectsOnlyNewWorkAndIdempotentResolutionSurvivesUntilCapacityRecovers() throws Exception {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.put("app.admission.max-backlog", "1"); properties.put("app.admission.burst-capacity", "20");
        try (var f = new WorkerFixture(properties)) {
            f.mock.route("/notify", e -> HttpMock.respond(e, 200, "ok"));
            var id = f.submit("backlog-first", "{}");
            String second = payload("tenant-a", "worker.notify", "backlog-second", "{}");
            var denied = f.api.post(second, ADMIN);
            assertEquals(503, denied.statusCode(), denied.body());
            assertEquals("BACKLOG_CAPACITY_EXCEEDED", f.api.json(denied).path("code").asText());
            assertEquals("1", denied.headers().firstValue("Retry-After").orElseThrow());
            assertEquals(1, f.api.taskCount()); assertEquals(0, f.mock.journal().size());
            var duplicate = f.api.post(payload("tenant-a", "worker.notify", "backlog-first", "{}"), ADMIN);
            assertEquals(200, duplicate.statusCode(), duplicate.body());
            assertEquals(id.toString(), f.api.json(duplicate).path("notificationId").asText());
            var conflict = f.api.post(payload("tenant-a", "worker.notify", "backlog-first", "{\"changed\":true}"), ADMIN);
            assertEquals(409, conflict.statusCode()); assertEquals(1, f.api.taskCount());
            assertEquals("pending", f.task(id).path("status").asText());
            assertEquals("succeeded", executeNextAttempt(f, id).path("status").asText());
            var acceptedLater = f.api.post(second, ADMIN);
            assertEquals(202, acceptedLater.statusCode(), acceptedLater.body());
            assertEquals(2, f.api.taskCount(), "Capacity recovery never deletes already acknowledged tasks");
            f.evidence("cp07-backlog-capacity-recovery", id, null);
        }
    }
}
