package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.JSON;
import static com.rc.notification.support.NotificationApiFixture.OPERATOR_A;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TC-WORK-05/08: exercise ownership fencing and recovery races through real PostgreSQL transactions. */
@Timeout(45)
class RepositoryOwnershipRaceTest {
    @ParameterizedTest(name = "stale deferral: {0}")
    @ValueSource(strings = {"expired-unrecovered", "replacement-lease", "replayed-run"})
    void staleDeferralCannotChangeAnyTaskOrAttemptField(String window) throws Exception {
        int budget = window.equals("replayed-run") ? 1 : 12;
        try (var fixture = new WorkerFixture(Map.of("app.delivery.max-attempts", Integer.toString(budget)))) {
            var id = fixture.submit("stale-defer-" + window, "{}");
            var oldLease = fixture.repository.claim("cp04-provider", "old-deferring-owner", Duration.ofMillis(800))
                    .orElseThrow();
            if (!window.equals("expired-unrecovered")) {
                fixture.repository.reserve(oldLease, Duration.ofMillis(400)).orElseThrow();
            }
            fixture.awaitExpiredLease(id, Duration.ofSeconds(4));

            Lease currentLease = null;
            if (!window.equals("expired-unrecovered")) {
                assertEquals(1, fixture.repository.recoverExpired(10));
                var recovered = fixture.attempts(id);
                assertEquals(1, recovered.size());
                assertEquals("UNKNOWN", recovered.get(0).path("outcome").asText());
                assertEquals("LEASE_EXPIRED", recovered.get(0).path("error_code").asText());
                assertFalse(recovered.get(0).path("ended_at").isNull());
                if (window.equals("replayed-run")) {
                    assertEquals("failed", fixture.task(id).path("status").asText(),
                            "The actual exhausted reservation is terminated by lease recovery");
                    var request = JSON.createObjectNode().put("replayRequestId", "stale-defer-replay")
                            .put("expectedRunNumber", 1).put("reason", "Verify the old round cannot defer the new round");
                    var replay = fixture.api.send("POST", "/admin/notifications/" + id + "/replay", request, OPERATOR_A);
                    assertEquals(200, replay.statusCode(), replay.body());
                    assertEquals(2, fixture.task(id).path("run_number").asInt());
                    assertEquals(0, fixture.task(id).path("run_attempt_count").asInt());
                    assertEquals(1, fixture.task(id).path("total_attempt_count").asLong());
                }
                currentLease = fixture.repository.claim("cp04-provider", "current-deferring-owner", Duration.ofSeconds(10))
                        .orElseThrow();
                assertNotEquals(oldLease.leaseToken(), currentLease.leaseToken());
                assertEquals(window.equals("replayed-run") ? 2 : 1, currentLease.runNumber());
                assertEquals(recovered, fixture.attempts(id), "A replacement claim does not reserve another attempt");
            } else {
                assertEquals("processing", fixture.task(id).path("status").asText());
                assertEquals(oldLease.leaseToken().toString(), fixture.task(id).path("lease_token").asText());
                assertTrue(fixture.attempts(id).isEmpty());
            }

            var beforeTask = fixture.task(id);
            var beforeHistory = fixture.attempts(id);
            assertFalse(fixture.repository.defer(oldLease, Duration.ofMinutes(1), List.of("120"), "QUOTA_COOLDOWN"));
            assertEquals(beforeTask, fixture.task(id), "Rejected stale deferral must preserve every persisted task field");
            assertEquals(beforeHistory, fixture.attempts(id), "Rejected stale deferral must preserve the entire attempt history");
            assertEquals(0, fixture.mock.journal().size());
            fixture.evidence("cp09-stale-defer-" + window, id, null);

            if (currentLease != null) {
                assertTrue(fixture.repository.defer(currentLease, Duration.ofSeconds(1), List.of(), "QUOTA_COOLDOWN"),
                        "The same pre-reservation deferral is permitted for the current owner");
                assertEquals(beforeHistory, fixture.attempts(id));
                assertEquals(beforeTask.path("run_attempt_count"), fixture.task(id).path("run_attempt_count"));
            }
        }
    }

    @Test
    void competingRecoverersAndClaimersCloseTheOldAttemptOnceAndCreateOneReplacementLease() throws Exception {
        try (var fixture = new WorkerFixture(Map.of("spring.jdbc.template.query-timeout", "10s"))) {
            var id = fixture.submit("concurrent-recovery-and-claim", "{}");
            var oldLease = fixture.repository.claim("cp04-provider", "expired-reserving-owner", Duration.ofMillis(800))
                    .orElseThrow();
            var firstAttempt = fixture.repository.reserve(oldLease, Duration.ofMillis(400)).orElseThrow();
            assertEquals(1, firstAttempt.attemptNo());
            fixture.awaitExpiredLease(id, Duration.ofSeconds(4));
            assertEquals(1, fixture.attempts(id).size());
            assertTrue(fixture.attempts(id).get(0).path("ended_at").isNull());

            long lockKey = id.getMostSignificantBits();
            fixture.api.sql("CREATE FUNCTION pause_ownership_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.error_code='LEASE_EXPIRED' AND OLD.ended_at IS NULL THEN PERFORM pg_advisory_xact_lock("
                    + lockKey + "); END IF; RETURN NEW; END $$");
            fixture.api.sql("CREATE TRIGGER pause_ownership_recovery BEFORE UPDATE ON notification_attempt "
                    + "FOR EACH ROW EXECUTE FUNCTION pause_ownership_recovery()");

            var executor = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var claimBarrier = new CyclicBarrier(2);
            try (var blocker = fixture.api.database.connection(); var lock = blocker.createStatement()) {
                blocker.setAutoCommit(false);
                int blockerPid;
                try (var row = lock.executeQuery("SELECT pg_backend_pid(),pg_advisory_xact_lock(" + lockKey + ")")) {
                    assertTrue(row.next());
                    blockerPid = row.getInt(1);
                }
                var one = executor.submit(() -> recoverThenClaim(fixture, "contender-one", ready, start, claimBarrier));
                var two = executor.submit(() -> recoverThenClaim(fixture, "contender-two", ready, start, claimBarrier));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                awaitBlockedRecovery(fixture, blockerPid);
                assertTrue(fixture.repository.claim("cp04-provider", "claim-during-recovery", Duration.ofSeconds(10)).isEmpty(),
                        "A concurrent claimer must not acquire the task while recovery owns its actual database row lock");
                assertEquals(oldLease.leaseToken().toString(), fixture.task(id).path("lease_token").asText());
                assertTrue(fixture.attempts(id).get(0).path("ended_at").isNull(),
                        "No partial recovery is visible before the history and task transaction commits");
                blocker.commit();
                var outcomes = List.of(one.get(8, TimeUnit.SECONDS), two.get(8, TimeUnit.SECONDS));
                assertEquals(1, outcomes.stream().mapToInt(RecoveryClaim::recovered).sum(),
                        "Concurrent recovery transactions may close the expired lease only once");
                assertEquals(1, outcomes.stream().filter(result -> result.lease().isPresent()).count());
                Lease replacement = outcomes.stream().flatMap(result -> result.lease().stream()).findFirst().orElseThrow();
                assertEquals(id, replacement.taskId());
                assertEquals(1, replacement.runNumber());
                assertNotEquals(oldLease.leaseToken(), replacement.leaseToken());
                var oldHistory = fixture.attempts(id);
                assertEquals(1, oldHistory.size());
                var closed = oldHistory.get(0);
                assertEquals("COMPLETED", closed.path("phase").asText());
                assertEquals("UNKNOWN", closed.path("outcome").asText());
                assertEquals("LEASE_EXPIRED", closed.path("error_code").asText());
                assertEquals(oldLease.leaseToken().toString(), closed.path("lease_token").asText());
                assertFalse(closed.path("ended_at").isNull());
                assertEquals(1, fixture.task(id).path("run_attempt_count").asInt());
                assertEquals(1, fixture.task(id).path("total_attempt_count").asLong());
                assertEquals(replacement.leaseToken().toString(), fixture.task(id).path("lease_token").asText());

                var beforeRepeatedRecovery = fixture.task(id);
                assertEquals(0, fixture.repository.recoverExpired(10));
                assertEquals(beforeRepeatedRecovery, fixture.task(id));
                assertEquals(oldHistory, fixture.attempts(id), "Repeated recovery cannot alter the old attempt's closing timestamp");
                var secondAttempt = fixture.repository.reserve(replacement, Duration.ofSeconds(5)).orElseThrow();
                assertEquals(2, secondAttempt.attemptNo());
                assertTrue(fixture.repository.reserve(replacement, Duration.ofSeconds(5)).isEmpty());
                var finalHistory = fixture.attempts(id);
                assertEquals(2, finalHistory.size());
                assertEquals(closed, finalHistory.get(0));
                assertEquals(2, finalHistory.get(1).path("attempt_no").asInt());
                assertEquals(replacement.leaseToken().toString(), finalHistory.get(1).path("lease_token").asText());
                assertTrue(finalHistory.get(1).path("ended_at").isNull());
                assertEquals(2, fixture.task(id).path("run_attempt_count").asInt());
                assertEquals(2, fixture.task(id).path("total_attempt_count").asLong());
                assertEquals(0, fixture.mock.journal().size(), "Repository recovery and reservation never perform business HTTP");
                fixture.evidence("cp09-recovery-claim-race", id, null);
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static RecoveryClaim recoverThenClaim(WorkerFixture fixture, String owner, CountDownLatch ready,
            CountDownLatch start, CyclicBarrier claimBarrier) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        int recovered = fixture.repository.recoverExpired(10);
        claimBarrier.await(5, TimeUnit.SECONDS);
        return new RecoveryClaim(recovered, fixture.repository.claim("cp04-provider", owner, Duration.ofSeconds(10)));
    }

    private static void awaitBlockedRecovery(WorkerFixture fixture, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = fixture.api.database.connection(); var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE " + blockerPid
                            + "=ANY(pg_blocking_pids(pid)) AND query LIKE '%UPDATE notification_attempt%')")) {
                assertTrue(rows.next());
                if (rows.getBoolean(1)) return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        fail("The real recovery transaction did not reach its database barrier");
    }

    private record RecoveryClaim(int recovered, Optional<Lease> lease) { }
}
