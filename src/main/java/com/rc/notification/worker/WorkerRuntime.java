package com.rc.notification.worker;

import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.storage.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Bounded ownership and execution. PostgreSQL remains the only queue of waiting notifications. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class WorkerRuntime implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerRuntime.class);
    private final DeliveryRepository repository;
    private final WorkerEngine engine;
    private final WorkerProperties properties;
    private final MeterRegistry metrics;
    private final String owner = "worker-" + UUID.randomUUID();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore slots;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refillQueued = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private String providerCursor;

    public WorkerRuntime(DeliveryRepository repository, WorkerEngine engine, WorkerProperties properties, MeterRegistry metrics) {
        this.repository = repository;
        this.engine = engine;
        this.properties = properties;
        this.metrics = metrics;
        int count = properties.globalConcurrency();
        slots = new Semaphore(count);
        AtomicInteger number = new AtomicInteger();
        executor = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(count), runnable -> {
            Thread thread = new Thread(runnable, "notification-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-poller");
            thread.setDaemon(true);
            return thread;
        });
        metrics.gauge("notification.worker.active", active);
    }

    @Override public void start() {
        if (closed.get() || !running.compareAndSet(false, true)) return;
        if (properties.schedulingEnabled()) scheduler.scheduleWithFixedDelay(this::scheduledTick,
                properties.pollInterval().toNanos(), properties.pollInterval().toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Useful for a controlled operator-driven scan as well as the regular scheduled scan. */
    public int tick() {
        if (!running.get() || !polling.compareAndSet(false, true)) return 0;
        int claimed = 0;
        try {
            int expired = repository.expirePending(100);
            if (expired > 0) metrics.counter("notification.worker.expired").increment(expired);
            int recovered = repository.recoverExpired(100);
            if (recovered > 0) metrics.counter("notification.worker.recovered").increment(recovered);
            List<String> providers = repository.dueProviders(providerCursor, 100);
            if (providers.isEmpty() && providerCursor != null) {
                providerCursor = null;
                providers = repository.dueProviders(null, 100);
            }
            boolean progress = true;
            int claimBudget = slots.availablePermits();
            while (running.get() && progress && claimed < claimBudget && slots.availablePermits() > 0) {
                progress = false;
                for (String provider : providers) {
                    if (!running.get() || claimed >= claimBudget || !slots.tryAcquire()) break;
                    Lease lease;
                    try { lease = repository.claim(provider, owner, properties.leaseDuration()).orElse(null); }
                    catch (RuntimeException failure) { slots.release(); throw failure; }
                    if (lease == null) { slots.release(); continue; }
                    providerCursor = provider;
                    active.incrementAndGet();
                    OwnedWork work = new OwnedWork(lease);
                    try { executor.execute(work); }
                    catch (RejectedExecutionException stopping) {
                        work.release(); // The durable T1 claim is recovered when its lease expires.
                        return claimed;
                    }
                    claimed++;
                    progress = true;
                }
            }
            return claimed;
        } finally { polling.set(false); }
    }

    private void scheduledTick() {
        try { tick(); }
        catch (RuntimeException failure) {
            metrics.counter("notification.worker.poll_failure").increment();
            LOG.warn("worker_poll_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    private final class OwnedWork implements Runnable {
        private final Lease lease;
        private final AtomicBoolean released = new AtomicBoolean();
        private OwnedWork(Lease lease) { this.lease = lease; }
        @Override public void run() {
            try { if (running.get()) engine.process(lease); }
            finally { release(); }
        }
        private void release() {
            if (released.compareAndSet(false, true)) {
                active.decrementAndGet();
                slots.release();
                requestRefill();
            }
        }
    }

    private void requestRefill() {
        if (!properties.schedulingEnabled() || !running.get() || !refillQueued.compareAndSet(false, true)) return;
        try { scheduler.execute(() -> { refillQueued.set(false); scheduledTick(); }); }
        catch (RejectedExecutionException stopping) { refillQueued.set(false); }
    }

    public int activeCount() { return active.get(); }
    public boolean isIdle() { return active.get() == 0 && !polling.get(); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return 100; }
    @Override public void stop() { close(); }
    @Override public void stop(Runnable callback) { try { close(); } finally { callback.run(); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        scheduler.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(properties.shutdownGracePeriod().toNanos(), TimeUnit.NANOSECONDS)) {
                for (Runnable waiting : executor.shutdownNow()) if (waiting instanceof OwnedWork work) work.release();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            for (Runnable waiting : executor.shutdownNow()) if (waiting instanceof OwnedWork work) work.release();
            Thread.currentThread().interrupt();
        }
    }
}
