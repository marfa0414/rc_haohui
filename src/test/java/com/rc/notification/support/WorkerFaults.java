package com.rc.notification.support;

import com.rc.notification.worker.Attempt;
import com.rc.notification.worker.Lease;
import com.rc.notification.worker.WorkerLifecycleListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only boundary latch. The real worker, repository, authentication and sender remain unchanged. */
public final class WorkerFaults implements WorkerLifecycleListener {
    private final AtomicReference<Phase> target = new AtomicReference<>();
    private volatile boolean hold;
    private volatile CountDownLatch reached = new CountDownLatch(1);
    private volatile CountDownLatch release = new CountDownLatch(1);
    public volatile Lease observedLease;
    public volatile Attempt observedAttempt;

    public void arm(Phase phase, boolean holdInsteadOfInterrupt) {
        reached = new CountDownLatch(1);
        release = new CountDownLatch(1);
        hold = holdInsteadOfInterrupt;
        target.set(phase);
    }

    public boolean awaitReached() throws InterruptedException { return reached.await(5, TimeUnit.SECONDS); }
    public void release() { release.countDown(); }

    @Override public void onPhase(Phase phase, Lease lease, Attempt attempt) throws InterruptedException {
        if (!target.compareAndSet(phase, null)) return;
        observedLease = lease;
        observedAttempt = attempt;
        reached.countDown();
        if (hold) {
            if (!release.await(12, TimeUnit.SECONDS)) throw new InterruptedException("Test boundary was never released");
        } else {
            throw new InterruptedException("Test-only simulated process interruption at durable boundary");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean WorkerFaults workerFaults() { return new WorkerFaults(); }
    }
}
