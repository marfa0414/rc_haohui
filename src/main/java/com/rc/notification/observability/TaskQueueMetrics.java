package com.rc.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A bounded background SELECT refreshes one cached snapshot; reading a gauge never obtains a DB connection. */
@Component
public final class TaskQueueMetrics implements SmartLifecycle, AutoCloseable {
    private static final String QUERY = """
            WITH observed AS MATERIALIZED (SELECT clock_timestamp() AS at)
            SELECT count(*) FILTER (WHERE status='pending') AS pending,
                   count(*) FILTER (WHERE status='processing') AS processing,
                   count(*) FILTER (WHERE status='succeeded') AS succeeded,
                   count(*) FILTER (WHERE status='failed') AS failed,
                   COALESCE(EXTRACT(EPOCH FROM (SELECT at FROM observed) -
                       min(next_attempt_at) FILTER (WHERE status='pending'
                           AND next_attempt_at <= (SELECT at FROM observed)
                           AND deadline_at > (SELECT at FROM observed)
                           AND run_attempt_count < max_attempts)), 0)::double precision AS oldest_due
            FROM notification_task
            """;
    private final JdbcTemplate jdbc;
    private final ObservabilityProperties properties;
    private final Counter failures;
    private final Timer successDuration;
    private final Timer failureDuration;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Observation observation = new Observation(null, false, 0);

    private record Snapshot(long pending, long processing, long succeeded, long failed, double oldestDue) { }
    private record Observation(Snapshot snapshot, boolean latestSucceeded, long collectedNanos) { }

    public TaskQueueMetrics(DataSource dataSource, ObservabilityProperties properties, MeterRegistry registry) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(2);
        this.properties = properties;
        failures = registry.counter("notification.queue.sample.failures");
        successDuration = Timer.builder("notification.queue.sample.duration").tag("result", "SUCCESS").register(registry);
        failureDuration = Timer.builder("notification.queue.sample.duration").tag("result", "FAILURE").register(registry);
        for (String status : List.of("pending", "processing", "succeeded", "failed")) {
            Gauge.builder("notification.tasks", this, value -> value.count(status)).tag("status", status).register(registry);
        }
        Gauge.builder("notification.queue.oldest_due.seconds", this, value -> {
            Snapshot snapshot = value.observation.snapshot();
            return snapshot == null ? Double.NaN : snapshot.oldestDue();
        }).register(registry);
        Gauge.builder("notification.queue.sample.success", this, value -> value.observation.latestSucceeded() ? 1 : 0).register(registry);
        Gauge.builder("notification.queue.sample.age.seconds", this, value -> {
            Observation observed = value.observation;
            return observed.snapshot() == null ? Double.NaN : Math.max(0, System.nanoTime() - observed.collectedNanos()) / 1_000_000_000.0;
        }).register(registry);
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "notification-metrics-sampler"); thread.setDaemon(true); return thread;
        });
    }

    private double count(String status) {
        Snapshot snapshot = observation.snapshot();
        if (snapshot == null) return Double.NaN;
        return switch (status) {
            case "pending" -> snapshot.pending();
            case "processing" -> snapshot.processing();
            case "succeeded" -> snapshot.succeeded();
            case "failed" -> snapshot.failed();
            default -> throw new IllegalArgumentException("Unknown task status");
        };
    }

    /** A controlled refresh is also useful to reconcile a known set of operations without timing sleeps. */
    public boolean refresh() {
        if (closed.get() || !refreshing.compareAndSet(false, true)) return false;
        long started = System.nanoTime();
        try {
            Snapshot next = jdbc.queryForObject(QUERY, (row, index) -> new Snapshot(row.getLong("pending"),
                    row.getLong("processing"), row.getLong("succeeded"), row.getLong("failed"), row.getDouble("oldest_due")));
            if (next == null) throw new IllegalStateException("Missing task metrics snapshot");
            observation = new Observation(next, true, System.nanoTime());
            successDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return true;
        } catch (RuntimeException unavailable) {
            Observation previous = observation;
            observation = new Observation(previous.snapshot(), false, previous.collectedNanos());
            failures.increment();
            failureDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return false;
        } finally { refreshing.set(false); }
    }

    @Override public void start() {
        if (closed.get() || !running.compareAndSet(false, true)) return;
        if (properties.samplingEnabled()) scheduler.scheduleWithFixedDelay(this::refresh, 0,
                properties.sampleInterval().toNanos(), TimeUnit.NANOSECONDS);
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return 0; }
    @Override public void stop() { close(); }
    @Override public void stop(Runnable callback) { try { close(); } finally { callback.run(); } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        scheduler.shutdownNow();
        try { scheduler.awaitTermination(Duration.ofSeconds(3).toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
