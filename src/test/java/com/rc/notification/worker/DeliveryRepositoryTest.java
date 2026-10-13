package com.rc.notification.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFixture;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP04 TC-WORK-01/05/06/07/08/09 transaction invariants against the actual repository and PostgreSQL. */
@Timeout(45)
class DeliveryRepositoryTest {
    @Test
    void competingClaimsHaveOneLeaseAndReservationAtomicallyCountsExactlyOnce() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("competing-claims", "{}");
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var first = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return fixture.repository.claim("cp04-provider", "one", Duration.ofSeconds(3)); });
                var second = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return fixture.repository.claim("cp04-provider", "two", Duration.ofSeconds(3)); });
                start.countDown();
                var claims = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertEquals(1, claims.stream().filter(java.util.Optional::isPresent).count());
                var lease = claims.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
                assertEquals(id, lease.taskId());
                assertEquals(0, fixture.task(id).path("run_attempt_count").asInt());
                assertTrue(fixture.attempts(id).isEmpty());
                var attempt = fixture.repository.reserve(lease, Duration.ofSeconds(2)).orElseThrow();
                assertEquals(1, attempt.attemptNo());
                assertTrue(fixture.repository.reserve(lease, Duration.ofSeconds(2)).isEmpty(),
                        "The same uncompleted lease cannot reserve a second attempt");
                assertEquals(1, fixture.task(id).path("run_attempt_count").asInt());
                assertEquals(1, fixture.task(id).path("total_attempt_count").asLong());
                assertEquals(1, fixture.attempts(id).size());
                assertEquals(0, fixture.mock.journal().size());
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }

    @Test
    void reservationRollbackCannotIncrementCountersWithoutCreatingAnAttempt() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("reserve-rollback", "{}");
            var lease = fixture.repository.claim("cp04-provider", "reserve-owner", Duration.ofSeconds(3)).orElseThrow();
            fixture.api.sql("CREATE FUNCTION test_block_attempt_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'test-only-attempt-insert-fault'; END $$");
            fixture.api.sql("CREATE TRIGGER test_block_attempt_insert BEFORE INSERT ON notification_attempt FOR EACH ROW EXECUTE FUNCTION test_block_attempt_insert()");
            assertThrows(RuntimeException.class, () -> fixture.repository.reserve(lease, Duration.ofSeconds(2)));
            assertEquals(0, fixture.task(id).path("run_attempt_count").asInt());
            assertEquals(0, fixture.task(id).path("total_attempt_count").asLong());
            assertTrue(fixture.attempts(id).isEmpty());
            fixture.api.sql("DROP TRIGGER test_block_attempt_insert ON notification_attempt");
            assertEquals(1, fixture.repository.reserve(lease, Duration.ofSeconds(2)).orElseThrow().attemptNo());
        }
    }

    @Test
    void wrongTokenWrongRunAndTerminalStateCannotReserveStartOrComplete() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("fenced-ownership", "{}");
            var lease = fixture.repository.claim("cp04-provider", "fence-owner", Duration.ofSeconds(3)).orElseThrow();
            var attempt = fixture.repository.reserve(lease, Duration.ofSeconds(2)).orElseThrow();
            var acknowledgement = new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now());
            for (Lease wrong : List.of(copy(lease, lease.runNumber(), UUID.randomUUID()), copy(lease, lease.runNumber() + 1, lease.leaseToken()))) {
                assertTrue(fixture.repository.reserve(wrong, Duration.ofSeconds(2)).isEmpty());
                assertFalse(fixture.repository.markRequestStarted(wrong, attempt, "fixture-v1"));
                assertFalse(fixture.repository.complete(wrong, attempt, acknowledgement));
            }
            assertEquals("processing", fixture.task(id).path("status").asText());
            assertEquals(1, fixture.attempts(id).size());
            assertTrue(fixture.repository.markRequestStarted(lease, attempt, "fixture-v1"));
            assertTrue(fixture.repository.complete(lease, attempt, acknowledgement));
            assertFalse(fixture.repository.complete(lease, attempt, acknowledgement));
            assertTrue(fixture.repository.reserve(lease, Duration.ofSeconds(2)).isEmpty());
            assertFalse(fixture.repository.markRequestStarted(lease, attempt, "fixture-v1"));
            assertEquals(1, fixture.task(id).path("run_attempt_count").asInt());
        }
    }

    @Test
    void anExpiredLeaseCannotWriteEvenBeforeARecoveryOrAnotherOwnerExists() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("expired-not-reclaimed", "{}");
            var lease = fixture.repository.claim("cp04-provider", "expired-owner", Duration.ofMillis(300)).orElseThrow();
            var attempt = fixture.repository.reserve(lease, Duration.ofMillis(200)).orElseThrow();
            fixture.awaitExpiredLease(id, Duration.ofSeconds(2));
            assertEquals(lease.leaseToken().toString(), fixture.task(id).path("lease_token").asText());
            assertEquals("processing", fixture.task(id).path("status").asText());
            assertTrue(fixture.repository.reserve(lease, Duration.ofMillis(100)).isEmpty());
            assertFalse(fixture.repository.markRequestStarted(lease, attempt, "fixture-v1"));
            assertFalse(fixture.repository.complete(lease, attempt, new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now())));
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals(0, fixture.repository.recoverExpired(100));
            assertEquals("pending", fixture.task(id).path("status").asText());
            assertEquals("UNKNOWN", fixture.attempts(id).get(0).path("outcome").asText());
        }
    }

    @Test
    void completionRollsBackBothTaskAndAttemptIfTheDatabaseRejectsEitherUpdate() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("completion-rollback", "{}");
            var lease = fixture.repository.claim("cp04-provider", "commit-owner", Duration.ofSeconds(3)).orElseThrow();
            var attempt = fixture.repository.reserve(lease, Duration.ofSeconds(2)).orElseThrow();
            assertTrue(fixture.repository.markRequestStarted(lease, attempt, "fixture-v1"));
            fixture.api.sql("CREATE FUNCTION test_block_success_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.status='succeeded' THEN RAISE EXCEPTION 'test-only-complete-fault'; END IF; RETURN NEW; END $$");
            fixture.api.sql("CREATE TRIGGER test_block_success_update BEFORE UPDATE ON notification_task FOR EACH ROW EXECUTE FUNCTION test_block_success_update()");
            var result = new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now());
            assertThrows(RuntimeException.class, () -> fixture.repository.complete(lease, attempt, result));
            assertEquals("processing", fixture.task(id).path("status").asText());
            var unfinished = fixture.attempts(id).get(0);
            assertTrue(unfinished.path("ended_at").isNull());
            assertEquals("UNKNOWN", unfinished.path("outcome").asText());
            fixture.api.sql("DROP TRIGGER test_block_success_update ON notification_task");
            assertTrue(fixture.repository.complete(lease, attempt, result));
            assertEquals("succeeded", fixture.task(id).path("status").asText());
            assertEquals("HTTP_ACKNOWLEDGED", fixture.attempts(id).get(0).path("outcome").asText());
        }
    }

    @Test
    void recoveryUsesRemainingBudgetAndDoesNotInventExtraAttempts() throws Exception {
        try (var fixture = new WorkerFixture()) {
            var id = fixture.submit("last-budget-reservation", "{}");
            fixture.api.sql("UPDATE notification_task SET max_attempts=1 WHERE id='" + id + "'");
            var lease = fixture.repository.claim("cp04-provider", "last-owner", Duration.ofMillis(300)).orElseThrow();
            fixture.repository.reserve(lease, Duration.ofMillis(200)).orElseThrow();
            fixture.awaitExpiredLease(id, Duration.ofSeconds(2));
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals(0, fixture.repository.recoverExpired(100));
            assertEquals("failed", fixture.task(id).path("status").asText());
            assertEquals(1, fixture.task(id).path("run_attempt_count").asInt());
            assertEquals(1, fixture.attempts(id).size());
            assertTrue(fixture.repository.claim("cp04-provider", "no-budget", Duration.ofSeconds(3)).isEmpty());
            assertEquals(0, fixture.mock.journal().size());
        }
    }

    @Test
    void aCompletionThatWaitsOnAnAttemptLockCannotCommitAfterItsLeaseExpires() throws Exception {
        try (var fixture = new WorkerFixture(Map.of("spring.jdbc.template.query-timeout", "10s"))) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "actual-recovery"));
            var id = fixture.submit("lock-wait-across-lease", "{}");
            var lease = fixture.repository.claim("cp04-provider", "blocked-old-owner", Duration.ofMillis(1500)).orElseThrow();
            // Longer execution budget deliberately isolates lease expiry from execution expiry.
            var attempt = fixture.repository.reserve(lease, Duration.ofSeconds(5)).orElseThrow();
            assertTrue(fixture.repository.markRequestStarted(lease, attempt, "fixture-v1"));
            var beforeTask = fixture.task(id);
            var beforeAttempt = fixture.attempts(id);
            var executor = Executors.newSingleThreadExecutor();
            try (var blocker = fixture.api.database.connection(); var statement = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var pid = statement.executeQuery("SELECT pg_backend_pid()")) { assertTrue(pid.next()); blockerPid = pid.getInt(1); }
                try (var row = statement.executeQuery("SELECT task_id FROM " + fixture.api.database.schema()
                        + ".notification_attempt WHERE task_id='" + id + "' FOR UPDATE")) { assertTrue(row.next()); }
                var completion = executor.submit(() -> fixture.repository.complete(lease, attempt,
                        new Completion("HTTP_ACKNOWLEDGED", 200, null, Instant.now())));
                boolean observedBlockedCompletion = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (System.nanoTime() < deadline) {
                    try (var observer = fixture.api.database.connection(); var probe = observer.createStatement();
                            var rows = probe.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE " + blockerPid
                                    + "=ANY(pg_blocking_pids(pid)) AND query LIKE '%UPDATE notification_attempt%')")) {
                        assertTrue(rows.next());
                        if (rows.getBoolean(1)) { observedBlockedCompletion = true; break; }
                    }
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
                }
                assertTrue(observedBlockedCompletion, "The real T4 must reach its blocked attempt UPDATE before lease expiry");
                fixture.awaitExpiredLease(id, Duration.ofSeconds(3));
                assertFalse(completion.isDone());
                blocker.commit();
                assertFalse(completion.get(3, TimeUnit.SECONDS), "Recheck actual database time after waiting for the row lock");
                assertEquals(beforeTask, fixture.task(id));
                assertEquals(beforeAttempt, fixture.attempts(id), "A rejected late T4 must roll back the history update too");
            } finally { executor.shutdownNow(); }
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals("UNKNOWN", fixture.attempts(id).get(0).path("outcome").asText());
            fixture.worker.tick();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(1, fixture.mock.journal().size(), "Only the recovered owner performs a physical request in this scenario");
            assertEquals(2, fixture.attempts(id).size());
        }
    }

    private static Lease copy(Lease lease, int runNumber, UUID token) {
        return new Lease(lease.taskId(), runNumber, token, lease.leaseOwner(), lease.leaseUntil(), lease.tenantId(),
                lease.operation(), lease.eventId(), lease.providerId(), lease.endpointGroup(), lease.authProfileId(), lease.snapshot());
    }
}
