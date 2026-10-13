package com.rc.notification.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFaults;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP04 TC-WORK-05/06/07/09: late ownership, round deadline, and result persistence failures. */
@Timeout(45)
class WorkerBoundaryTest {
    @Test
    void anAttemptStartedBeforeTheRoundDeadlineCanPersistItsLaterAcknowledgement() throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new WorkerFixture(Map.of("app.worker.business-timeout", "2s",
                "app.worker.attempt-timeout", "3s", "app.worker.lease-duration", "5s"))) {
            fixture.mock.route("/notify", exchange -> {
                arrived.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("deadline barrier timeout");
                    HttpMock.respond(exchange, 200, "on-time-started-attempt");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            var id = fixture.submit("deadline-crossing", "{}");
            fixture.api.sql("UPDATE notification_task SET deadline_at=clock_timestamp()+interval '500 milliseconds' WHERE id='" + id + "'");
            fixture.worker.tick();
            assertTrue(arrived.await(1, TimeUnit.SECONDS));
            awaitDatabaseCondition(fixture, id, "deadline_at <= clock_timestamp()", Duration.ofSeconds(2));
            release.countDown();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(3));
            assertEquals(1, fixture.mock.journal().size());
            assertEquals(1, fixture.attempts(id).size());
        } finally { release.countDown(); }
    }

    @Test
    void deadlineAfterClaimButBeforeReservationDoesNotGrantANewAttempt() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("deadline-no-new-reserve", "{}");
            fixture.api.sql("UPDATE notification_task SET deadline_at=clock_timestamp()+interval '200 milliseconds' WHERE id='" + id + "'");
            var lease = fixture.repository.claim("cp04-provider", "deadline-owner", Duration.ofMillis(500)).orElseThrow();
            awaitDatabaseCondition(fixture, id, "deadline_at <= clock_timestamp()", Duration.ofSeconds(2));
            assertTrue(fixture.repository.reserve(lease, Duration.ofMillis(100)).isEmpty());
            assertEquals(0, fixture.task(id).path("run_attempt_count").asInt());
            assertTrue(fixture.attempts(id).isEmpty());
            fixture.awaitExpiredLease(id, Duration.ofSeconds(2));
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals("failed", fixture.task(id).path("status").asText());
            assertEquals(0, fixture.task(id).path("total_attempt_count").asLong());
            assertEquals(0, fixture.mock.journal().size());
        }
    }

    @Test
    void aLateWorkerCannotOverwriteARecoveredWorkersSuccess() throws Exception {
        WorkerFaults faults = null;
        try (var fixture = new WorkerFixture(WorkerFaults.Configuration.class)) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "ok"));
            faults = fixture.api.application.getBean(WorkerFaults.class);
            faults.arm(WorkerLifecycleListener.Phase.AFTER_HTTP, true);
            var id = fixture.submit("late-owner", "{}");
            fixture.worker.tick();
            assertTrue(faults.awaitReached());
            Lease oldLease = faults.observedLease;
            Attempt oldAttempt = faults.observedAttempt;
            fixture.awaitExpiredLease(id, Duration.ofSeconds(5));
            assertEquals(1, fixture.repository.recoverExpired(100));
            fixture.worker.tick();
            var succeeded = fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertFalse(fixture.repository.complete(oldLease, oldAttempt, new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now())));
            assertFalse(fixture.repository.complete(oldLease, oldAttempt, new Completion("UNKNOWN", null, "LATE_FAILURE", Instant.now())));
            assertTrue(fixture.repository.reserve(oldLease, Duration.ofSeconds(1)).isEmpty());
            assertEquals(succeeded, fixture.task(id), "All late writes must leave the new owner's row untouched");
            assertEquals(2, fixture.mock.journal().size());
            assertEquals(2, fixture.attempts(id).size());
            assertEquals("UNKNOWN", fixture.attempts(id).get(0).path("outcome").asText());
            assertEquals("HTTP_ACKNOWLEDGED", fixture.attempts(id).get(1).path("outcome").asText());
            faults.release();
            fixture.awaitIdle(Duration.ofSeconds(3));
            assertEquals(succeeded, fixture.task(id), "The actual released old worker must also be fenced at T4");
        } finally { if (faults != null) faults.release(); }
    }

    /** TC-WORK-10: stop claims, cancel an actual in-flight socket and preserve recoverable ownership. */
    @Test
    void shutdownStopsNewClaimsAndCancelsAnInflightHttpWithinABound() throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new WorkerFixture(Map.of("app.worker.global-concurrency", "1",
                "app.worker.business-timeout", "4s", "app.worker.attempt-timeout", "5s",
                "app.worker.lease-duration", "6s", "app.worker.shutdown-grace-period", "0s"))) {
            fixture.mock.route("/notify", exchange -> {
                arrived.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) throw new java.io.IOException("shutdown barrier timeout");
                    HttpMock.respond(exchange, 200, "response-after-cancel");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            var running = fixture.submit("shutdown-active", "{}");
            var waiting = fixture.submit("shutdown-waiting", "{}");
            assertEquals(1, fixture.worker.tick());
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.worker.activeCount());
            long started = System.nanoTime();
            fixture.worker.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(2500)) < 0,
                    "Zero grace shutdown must actively cancel HTTP, not wait for its four-second timeout");
            fixture.awaitIdle(Duration.ofSeconds(1));
            assertEquals(0, fixture.worker.tick());
            assertFalse(fixture.worker.isRunning());
            assertEquals(1, fixture.mock.journal().size());
            assertEquals("processing", fixture.task(running).path("status").asText());
            assertEquals("pending", fixture.task(waiting).path("status").asText());
            assertTrue(fixture.attempts(running).get(0).path("ended_at").isNull());
            assertTrue(fixture.attempts(waiting).isEmpty());
            release.countDown();
            fixture.awaitExpiredLease(running, Duration.ofSeconds(7));
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals("pending", fixture.task(running).path("status").asText());
            assertEquals("UNKNOWN", fixture.attempts(running).get(0).path("outcome").asText());
            assertEquals(0, fixture.worker.tick());
            assertEquals(1, fixture.mock.journal().size());
        } finally { release.countDown(); }
    }

    @Test
    void aSuccessfulHttpWithFailedCommitWaitsForRecoveryInsteadOfSendingAgainInTheSameAttempt() throws Exception {
        try (var fixture = new WorkerFixture()) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "real-http-success"));
            fixture.api.sql("CREATE FUNCTION test_fail_result_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.status='succeeded' THEN RAISE EXCEPTION 'test-only-writeback-fault'; END IF; RETURN NEW; END $$");
            fixture.api.sql("CREATE TRIGGER test_fail_result_commit BEFORE UPDATE ON notification_task FOR EACH ROW EXECUTE FUNCTION test_fail_result_commit()");
            var id = fixture.submit("http-success-db-failure", "{}");
            fixture.worker.tick();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (fixture.mock.journal().isEmpty() && System.nanoTime() < deadline) LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            assertEquals(1, fixture.mock.journal().size());
            // Waiting for the lease also gives the bounded HTTP execution and failed transaction time to leave the worker.
            fixture.awaitExpiredLease(id, Duration.ofSeconds(5));
            assertEquals("processing", fixture.task(id).path("status").asText());
            assertEquals(1, fixture.task(id).path("run_attempt_count").asInt());
            assertEquals(1, fixture.mock.journal().size(), "A failed T4 must never create a hidden second send in its attempt");
            assertTrue(fixture.attempts(id).get(0).path("ended_at").isNull());
            assertEquals("UNKNOWN", fixture.attempts(id).get(0).path("outcome").asText());
            fixture.api.sql("DROP TRIGGER test_fail_result_commit ON notification_task");
            assertEquals(1, fixture.repository.recoverExpired(100));
            fixture.worker.tick();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(2, fixture.mock.journal().size());
            assertEquals(2, fixture.attempts(id).size());
        }
    }

    private static void awaitDatabaseCondition(WorkerFixture fixture, UUID id, String condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            try (var connection = fixture.api.database.connection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT " + condition + " FROM " + fixture.api.database.schema()
                            + ".notification_task WHERE id='" + id + "'")) {
                assertTrue(rows.next());
                if (rows.getBoolean(1)) return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        } while (System.nanoTime() < deadline);
        fail("PostgreSQL condition did not become true: " + condition);
    }
}
