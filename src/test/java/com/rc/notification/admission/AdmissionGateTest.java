package com.rc.notification.admission;

import static org.junit.jupiter.api.Assertions.*;
import com.rc.notification.api.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP07 TC-API-08/TC-GUARD-08: bounded process-local token bucket; no cluster-rate guarantee. */
@Timeout(10)
class AdmissionGateTest {
    @Test void refillIsMonotonicAndCappedAtTheConfiguredBurst() {
        var time = new AtomicLong();
        var metrics = new SimpleMeterRegistry();
        try {
            var gate = new AdmissionGate(new AdmissionProperties(2.0, 2, 3L), time::get, metrics);
            assertTrue(gate.tryAcquire().allowed()); assertTrue(gate.tryAcquire().allowed()); assertFalse(gate.tryAcquire().allowed());
            time.addAndGet(Duration.ofMillis(499).toNanos()); assertFalse(gate.tryAcquire().allowed());
            time.addAndGet(Duration.ofMillis(1).toNanos()); assertTrue(gate.tryAcquire().allowed()); assertFalse(gate.tryAcquire().allowed());
            time.addAndGet(Duration.ofDays(1).toNanos()); assertTrue(gate.tryAcquire().allowed()); assertTrue(gate.tryAcquire().allowed());
            assertFalse(gate.tryAcquire().allowed()); assertEquals(3L, gate.backlogLimit());
            gate.requireBacklogCapacity(2); assertThrows(ApiException.class, () -> gate.requireBacklogCapacity(3));
        } finally { metrics.close(); }
    }
    @Test void fiftyConcurrentCallsCannotExceedOneSharedLocalBurst() throws Exception {
        var time = new AtomicLong();
        var metrics = new SimpleMeterRegistry();
        try {
            var gate = new AdmissionGate(new AdmissionProperties(1.0, 10, 100L), time::get, metrics);
            var executor = Executors.newFixedThreadPool(50); var start = new CountDownLatch(1);
            try {
                var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int n = 0; n < 50; n++) results.add(executor.submit(() -> { assertTrue(start.await(2, TimeUnit.SECONDS)); return gate.tryAcquire().allowed(); }));
                start.countDown(); int allowed = 0;
                for (var result : results) if (result.get(2, TimeUnit.SECONDS)) allowed++;
                assertEquals(10, allowed); assertEquals(40.0, metrics.get("notification.admission").tag("result", "RATE_LIMITED").counter().count());
            } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
        } finally { metrics.close(); }
    }
}
