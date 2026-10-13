package com.rc.notification.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.WorkerFaults;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CP04 TC-WORK-02/03/04: durable boundary interruption and actual lease recovery; independent process kill is CP09. */
@Timeout(45)
class WorkerCrashWindowTest {
    @ParameterizedTest
    @EnumSource(value = WorkerLifecycleListener.Phase.class, names = {"AFTER_CLAIM", "AFTER_RESERVE", "AFTER_REQUEST_STARTED"})
    void interruptionBeforeHttpPreservesTheRightBudgetAndRecoversByRealDelivery(WorkerLifecycleListener.Phase phase)
            throws Exception {
        try (var fixture = new WorkerFixture(WorkerFaults.Configuration.class)) {
            fixture.mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "ok"));
            var faults = fixture.api.application.getBean(WorkerFaults.class);
            faults.arm(phase, false);
            var id = fixture.submit("interrupted-" + phase.name(), "{}");
            fixture.worker.tick();
            assertTrue(faults.awaitReached());
            boolean reserved = phase != WorkerLifecycleListener.Phase.AFTER_CLAIM;
            var interrupted = fixture.task(id);
            assertEquals("processing", interrupted.path("status").asText());
            assertEquals(reserved ? 1 : 0, interrupted.path("run_attempt_count").asInt());
            assertEquals(reserved ? 1 : 0, fixture.attempts(id).size());
            assertEquals(0, fixture.mock.journal().size());
            if (reserved) {
                var attempt = fixture.attempts(id).get(0);
                assertEquals(phase == WorkerLifecycleListener.Phase.AFTER_REQUEST_STARTED ? "UNKNOWN" : "NOT_SENT", attempt.path("outcome").asText());
            }
            fixture.evidence("cp04-" + phase.name().toLowerCase() + "-interrupted", id, 0);
            fixture.awaitExpiredLease(id, Duration.ofSeconds(5));
            assertEquals(1, fixture.repository.recoverExpired(100));
            assertEquals(0, fixture.repository.recoverExpired(100), "Recovery must be idempotent");
            assertEquals("pending", fixture.task(id).path("status").asText());
            if (reserved) {
                var recovered = fixture.attempts(id).get(0);
                assertEquals("UNKNOWN", recovered.path("outcome").asText(),
                        "A process interruption cannot prove whether a reserved attempt reached the supplier");
                assertEquals("LEASE_EXPIRED", recovered.path("error_code").asText());
                assertFalse(recovered.path("ended_at").isNull());
            }
            fixture.worker.tick();
            var completed = fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(reserved ? 2 : 1, completed.path("run_attempt_count").asInt());
            assertEquals(reserved ? 2 : 1, completed.path("total_attempt_count").asLong());
            assertEquals(reserved ? 2 : 1, fixture.attempts(id).size());
            assertEquals(1, fixture.mock.journal().size());
            fixture.evidence("cp04-" + phase.name().toLowerCase() + "-recovered", id, 1);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void supplierAcknowledgementBeforePersistenceCanDuplicateRequestsButNotAnIdempotentSuppliersEffect(boolean supplierDeduplicates)
            throws Exception {
        Set<String> appliedEvents = ConcurrentHashMap.newKeySet();
        var sideEffects = new AtomicInteger();
        try (var fixture = new WorkerFixture(WorkerFaults.Configuration.class)) {
            fixture.mock.route("/notify", exchange -> {
                String event = exchange.getRequestHeaders().getFirst("X-Event-Id");
                if (!supplierDeduplicates || appliedEvents.add(event)) sideEffects.incrementAndGet();
                HttpMock.respond(exchange, 200, "already-applied-or-created");
            });
            var faults = fixture.api.application.getBean(WorkerFaults.class);
            faults.arm(WorkerLifecycleListener.Phase.AFTER_HTTP, false);
            var id = fixture.submit("side-effect-event", "{}");
            fixture.worker.tick();
            assertTrue(faults.awaitReached());
            assertEquals(1, fixture.mock.journal().size());
            assertEquals(1, sideEffects.get());
            assertEquals("processing", fixture.task(id).path("status").asText());
            assertEquals("UNKNOWN", fixture.attempts(id).get(0).path("outcome").asText());
            fixture.evidence("cp04-after-http-" + supplierDeduplicates + "-interrupted", id, sideEffects.get());
            fixture.awaitExpiredLease(id, Duration.ofSeconds(5));
            assertEquals(1, fixture.repository.recoverExpired(100));
            fixture.worker.tick();
            fixture.awaitStatus(id, "succeeded", Duration.ofSeconds(5));
            assertEquals(2, fixture.mock.journal().size());
            assertEquals(supplierDeduplicates ? 1 : 2, sideEffects.get());
            assertEquals(fixture.mock.journal().get(0).firstHeader("X-Event-Id"), fixture.mock.journal().get(1).firstHeader("X-Event-Id"));
            assertArrayEquals(fixture.mock.journal().get(0).body(), fixture.mock.journal().get(1).body());
            assertEquals(2, fixture.attempts(id).size());
            fixture.evidence("cp04-after-http-" + supplierDeduplicates + "-recovered", id, sideEffects.get());
        }
    }
}
