package com.rc.notification.worker;

import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP04 TC-API-01, TC-MAP-01/02, TC-AUTH-01 and TC-WORK-01 physical delivery portions. */
@Timeout(45)
class WorkerDeliveryTest {
    @Test
    void allModeSchedulesAcceptedWorkWithoutManualTicksAndDoesNotWaitForTheSupplier() throws Exception {
        var requestArrived = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        try (var fixture = new WorkerFixture(java.util.Map.of(
                "app.worker.scheduling-enabled", "true", "app.worker.poll-interval", "100ms",
                "app.worker.business-timeout", "2s", "app.worker.attempt-timeout", "3s", "app.worker.lease-duration", "5s"))) {
            fixture.mock.route("/notify", exchange -> {
                requestArrived.countDown();
                try {
                    if (!releaseResponse.await(4, TimeUnit.SECONDS)) throw new java.io.IOException("response barrier timeout");
                    HttpMock.respond(exchange, 200, "ok");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            long started = System.nanoTime();
            var id = fixture.submit("automatic-scheduler-event", "{}");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(1500)) < 0,
                    "202 acceptance must return while the supplier response remains blocked");
            assertEquals(1, releaseResponse.getCount());
            assertTrue(requestArrived.await(2, TimeUnit.SECONDS), "APP_MODE=all must actually register and run its scheduler");
            releaseResponse.countDown();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(1, fixture.mock.journal().size());
        } finally { releaseResponse.countDown(); }
    }

    @Test
    void acceptedTaskIsDeliveredAsynchronouslyWithExactSnapshotAndRuntimeCredential() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "{\"businessStatus\":\"ignored\"}"));
            var id = fixture.submit("physical-json-event", "{\"payload\":{\"name\":\"中文\\n\\\"quoted\\\"\",\"large\":123456789012345678901}} ");
            assertEquals("pending", fixture.task(id).path("status").asText());
            assertEquals(0, fixture.mock.journal().size(), "Acceptance never waits for or calls the supplier");
            byte[] persisted = fixture.configuration.bodyBytes(id.toString());
            fixture.worker.tick();
            var succeeded = fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(1, succeeded.path("run_attempt_count").asInt());
            assertEquals(1, succeeded.path("total_attempt_count").asLong());
            assertEquals("HTTP_ACKNOWLEDGED", succeeded.path("last_outcome").asText());
            assertEquals(1, fixture.mock.journal().size());
            var received = fixture.mock.journal().get(0);
            assertArrayEquals(persisted, received.body());
            assertEquals("application/json", received.firstHeader("Content-Type"));
            assertEquals("physical-json-event", received.firstHeader("X-Event-Id"));
            assertEquals("Key cp04-test-only-secret", received.firstHeader("X-Api-Key"));
            assertNull(received.firstHeader("Authorization"), "Internal bearer credentials are never supplier credentials");
            var attempts = fixture.attempts(id);
            assertEquals(1, attempts.size());
            assertEquals(1, attempts.get(0).path("attempt_no").asInt());
            assertEquals("HTTP_ACKNOWLEDGED", attempts.get(0).path("outcome").asText());
            assertFalse(attempts.get(0).path("request_started_at").isNull());
            assertEquals("fixture-v1", attempts.get(0).path("credential_version").asText());
            assertFalse(succeeded.toString().contains("cp04-test-only-secret"));
            assertFalse(attempts.toString().contains("cp04-test-only-secret"));
            fixture.worker.tick();
            assertEquals(1, fixture.mock.journal().size(), "A terminal task is not sent again by later ticks");
        }
    }

    @Test
    void configurationOnlyFormSupplierReceivesThePersistedEncoding() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/form", exchange -> HttpMock.respond(exchange, 200, "ok"));
            var input = template("tenant-a", "form", JSON.readTree("""
                    {"name":{"from":"data.name","type":"string","required":true},
                     "event":{"from":"eventId","type":"string","required":true}}
                    """)).put("providerId", "cp04-form-provider").put("url", fixture.mock.uri("/form").toString());
            var auth = apiKey("tenant-a").put("headerName", "X-Second-Key").put("secretRef", "env:CP04_TEST_SECRET").put("prefix", "");
            fixture.configuration.install(input, auth, "worker.form");
            var accepted = fixture.api.post(payload("tenant-a", "worker.form", "physical-form-event", "{\"name\":\"中 文&+%\"}"), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var id = java.util.UUID.fromString(fixture.api.json(accepted).path("notificationId").asText());
            fixture.worker.tick();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(1, fixture.mock.count("/form"));
            var received = fixture.mock.journal().get(0);
            assertArrayEquals(fixture.configuration.bodyBytes(id.toString()), received.body());
            assertEquals("application/x-www-form-urlencoded", received.firstHeader("Content-Type"));
            assertEquals("cp04-test-only-secret", received.firstHeader("X-Second-Key"));
            String decoded = java.net.URLDecoder.decode(new String(received.body(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertTrue(decoded.contains("name=中 文&+%"));
            assertTrue(decoded.contains("event=physical-form-event"));
        }
    }

    @Test
    void supplierWaitDoesNotHoldTaskLocksAndGlobalExecutionCapacityIsBounded() throws Exception {
        var arrived = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var fixture = new WorkerFixture(java.util.Map.of(
                "app.worker.business-timeout", "3s", "app.worker.attempt-timeout", "4s", "app.worker.lease-duration", "6s"))) {
            fixture.mock.route("/notify", exchange -> {
                arrived.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("supplier barrier timeout");
                    HttpMock.respond(exchange, 200, "ok");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            var first = fixture.submit("capacity-first", "{}");
            var second = fixture.submit("capacity-second", "{}");
            var third = fixture.submit("capacity-third", "{}");
            fixture.worker.tick();
            fixture.worker.tick();
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            fixture.worker.tick();
            assertEquals(2, fixture.mock.journal().size(), "No third request may exceed the two reserved execution slots");
            try (var connection = fixture.api.database.connection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT id FROM " + fixture.api.database.schema()
                            + ".notification_task WHERE status='processing' FOR UPDATE NOWAIT")) {
                int locks = 0;
                while (rows.next()) locks++;
                assertEquals(2, locks, "Slow network work must not retain either task's database lock");
            }
            release.countDown();
            fixture.awaitStatus(first, "succeeded", Duration.ofSeconds(5));
            fixture.awaitStatus(second, "succeeded", Duration.ofSeconds(5));
            fixture.awaitIdle(Duration.ofSeconds(2));
            fixture.worker.tick();
            fixture.awaitStatus(third, "succeeded", Duration.ofSeconds(5));
            assertEquals(3, fixture.mock.journal().size());
        } finally { release.countDown(); }
    }

    /** CP04 TC-SEC-01/02 basic connector gate; exhaustive rebinding and DNS variants remain CP08. */
    @Test
    void anAllowedHttpsNumericHostStillFailsTheRealPrivateAddressConnectionCheckWithoutSending() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/private", exchange -> HttpMock.respond(exchange, 200, "must-not-arrive"));
            var template = scalarTemplate("tenant-a", "private-target")
                    .put("url", fixture.mock.uri("/private").toString().replace("http://", "https://"))
                    .put("providerId", "cp04-private-provider");
            var auth = apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET");
            fixture.configuration.install(template, auth, "worker.private");
            var accepted = fixture.api.post(payload("tenant-a", "worker.private", "blocked-numeric-host", "{}"), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var id = java.util.UUID.fromString(fixture.api.json(accepted).path("notificationId").asText());
            fixture.worker.tick();
            var failed = fixture.awaitStatus(id, "failed", Duration.ofSeconds(5));
            assertEquals("NOT_SENT", failed.path("last_outcome").asText());
            assertEquals("HTTP_TARGET_REJECTED", failed.path("last_error_code").asText());
            assertEquals(1, fixture.attempts(id).size());
            assertEquals(0, fixture.mock.journal().size(), "Numeric IP routing must not bypass actual address validation");
        }
    }

    @Test
    void aMissingRuntimeSecretFailsDiagnosticallyWithoutCallingTheSupplier() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/no-secret", exchange -> HttpMock.respond(exchange, 200, "must-not-arrive"));
            var input = scalarTemplate("tenant-a", "missing-secret").put("providerId", "cp04-secret-provider")
                    .put("url", fixture.mock.uri("/no-secret").toString());
            var auth = apiKey("tenant-a").put("secretRef", "env:CP04_INTENTIONALLY_MISSING_SECRET");
            assertNull(System.getenv("CP04_INTENTIONALLY_MISSING_SECRET"), "The negative fixture's fake secret must be absent");
            fixture.configuration.install(input, auth, "worker.missing-secret");
            var accepted = fixture.api.post(payload("tenant-a", "worker.missing-secret", "missing-secret-event", "{}"), ADMIN);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var id = java.util.UUID.fromString(fixture.api.json(accepted).path("notificationId").asText());
            fixture.worker.tick();
            var failed = fixture.awaitStatus(id, "failed", Duration.ofSeconds(5));
            assertEquals("NOT_SENT", failed.path("last_outcome").asText());
            assertEquals("AUTH_CONFIGURATION_ERROR", failed.path("last_error_code").asText());
            assertEquals(1, failed.path("run_attempt_count").asInt());
            assertEquals(1, fixture.attempts(id).size());
            assertTrue(fixture.attempts(id).get(0).path("request_started_at").isNull());
            assertTrue(fixture.attempts(id).get(0).path("http_send_started_at").isNull());
            assertEquals(0, fixture.mock.journal().size());
        }
    }

    @Test
    void aManualTickHasAFixedClaimBudgetEvenWhenFastRequestsReturnSlotsDuringTheTick() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "immediate-response"));
            var tasks = new java.util.ArrayList<java.util.UUID>();
            for (int index = 0; index < 12; index++) tasks.add(fixture.submit("bounded-tick-" + index, "{}"));
            // Slow only the SQL claim, not the supplier: while T1 for job 2 is executing, job 1 can
            // finish and return its execution slot. Reusing that returned slot must not extend this tick.
            fixture.api.sql("CREATE FUNCTION test_overlap_claim_and_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF OLD.status='pending' AND NEW.status='processing' THEN PERFORM pg_sleep(0.1); END IF; RETURN NEW; END $$");
            fixture.api.sql("CREATE TRIGGER test_overlap_claim_and_completion BEFORE UPDATE ON notification_task FOR EACH ROW "
                    + "EXECUTE FUNCTION test_overlap_claim_and_completion()");
            assertEquals(2, fixture.worker.tick(), "The initial two free slots define the entire tick's claim budget");
            fixture.awaitIdle(Duration.ofSeconds(5));
            assertEquals(2, fixture.mock.journal().size(), "A returned slot cannot cause additional claims in the same manual tick");
            int pending = 0;
            for (var id : tasks) if (fixture.task(id).path("status").asText().equals("pending")) pending++;
            assertEquals(10, pending);
            for (int batch = 1; batch < 6; batch++) {
                assertEquals(2, fixture.worker.tick());
                fixture.awaitIdle(Duration.ofSeconds(5));
                assertEquals((batch + 1) * 2, fixture.mock.journal().size());
            }
            assertEquals(0, fixture.worker.tick());
            for (var id : tasks) {
                assertEquals("succeeded", fixture.task(id).path("status").asText());
                assertEquals(1, fixture.attempts(id).size());
            }
        }
    }
}
