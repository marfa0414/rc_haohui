package com.rc.notification.admission;

import com.rc.notification.api.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One bounded, process-local token bucket; no attacker-controlled per-tenant map. */
@Component
public class AdmissionGate {
    private final AdmissionProperties properties;
    private final LongSupplier monotonic;
    private final MeterRegistry metrics;
    private double tokens;
    private long refreshedAt;

    @Autowired public AdmissionGate(AdmissionProperties properties, MeterRegistry metrics) {
        this(properties, System::nanoTime, metrics);
    }
    public AdmissionGate(AdmissionProperties properties, LongSupplier monotonic, MeterRegistry metrics) {
        this.properties = properties;
        this.monotonic = monotonic;
        this.metrics = metrics;
        this.tokens = properties.burstCapacity();
        this.refreshedAt = monotonic.getAsLong();
    }
    public record Decision(boolean allowed, long retryAfterSeconds) { }
    public synchronized Decision tryAcquire() {
        long now = monotonic.getAsLong();
        long elapsed = now - refreshedAt;
        if (elapsed > 0) {
            tokens = Math.min(properties.burstCapacity(), tokens + (elapsed / 1_000_000_000.0) * properties.requestsPerSecond());
            refreshedAt = now;
        }
        if (tokens >= 1) {
            tokens -= 1;
            metrics.counter("notification.admission", "result", "ALLOWED").increment();
            return new Decision(true, 0);
        }
        long retry = Math.max(1, (long) Math.ceil((1 - tokens) / properties.requestsPerSecond()));
        metrics.counter("notification.admission", "result", "RATE_LIMITED").increment();
        return new Decision(false, retry);
    }
    public void requireRateCapacity() {
        Decision decision = tryAcquire();
        if (!decision.allowed()) throw new ApiException(429, "ADMISSION_RATE_LIMITED", "Submission rate capacity is exhausted", decision.retryAfterSeconds());
    }
    public long backlogLimit() { return properties.maxBacklog(); }
    public void requireBacklogCapacity(long observed) {
        if (observed >= properties.maxBacklog()) {
            metrics.counter("notification.admission", "result", "BACKLOG_REJECTED").increment();
            throw new ApiException(503, "BACKLOG_CAPACITY_EXCEEDED", "Pending delivery capacity is exhausted", 1L);
        }
    }
}
