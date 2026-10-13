package com.rc.notification.protection;

import com.rc.notification.delivery.BusinessHttpSender.FailureKind;
import com.rc.notification.retry.RetryAfter;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.worker.Lease;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Process-local protection. Its short lock never covers database, HTTP, or token preparation. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class ProtectionGate {
    private static final Duration FOREVER = Duration.ofNanos(Long.MAX_VALUE);
    private static final RuntimeException BUSINESS_FAILURE = new BusinessFailure();
    private final Object lock = new Object();
    private final ProtectionProperties properties;
    private final String environment;
    private final MeterRegistry metrics;
    private final Clock wall;
    private final LongSupplier monotonic;
    private final DoubleSupplier random;
    private final CircuitBreakerConfig circuitConfig;
    private final Map<CircuitKey, CircuitEntry> circuits = new HashMap<>();
    private final Map<QuotaKey, QuotaEntry> quotas = new HashMap<>();
    private final Map<AuthKey, String> sharedQuotas;

    public record Admission(Permit permit, Duration delay, String reason) {
        public boolean admitted() { return permit != null; }
    }
    public record CircuitSnapshot(String state, int bufferedCalls, int failedCalls, int activePermits, long generation) { }
    private record CircuitKey(String providerId, String endpointGroup) { }
    private record AuthKey(String tenantId, UUID profileId) { }
    private record QuotaKey(String environment, String tenantId, UUID profileId, String sharedGroup) { }

    private static final class CircuitEntry {
        final CircuitBreaker breaker;
        long generation;
        long openedAt;
        long lastUsed;
        int active;
        CircuitEntry(CircuitBreaker breaker, long now) { this.breaker = breaker; lastUsed = now; }
    }
    private static final class QuotaEntry {
        long started;
        Duration duration = Duration.ZERO;
        boolean forever;
        int active;
    }
    private static final class BusinessFailure extends RuntimeException {
        BusinessFailure() { super("Business transport failure", null, false, false); }
    }

    @Autowired
    public ProtectionGate(ProtectionProperties properties, @Value("${app.environment:production}") String environment,
                          MeterRegistry metrics) {
        this(properties, environment, metrics, Clock.systemUTC(), System::nanoTime,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    public ProtectionGate(ProtectionProperties properties, String environment, MeterRegistry metrics,
                          Clock wall, LongSupplier monotonic, DoubleSupplier random) {
        this.properties = Objects.requireNonNull(properties);
        this.environment = Objects.requireNonNull(environment);
        this.metrics = Objects.requireNonNull(metrics);
        this.wall = Objects.requireNonNull(wall);
        this.monotonic = Objects.requireNonNull(monotonic);
        this.random = Objects.requireNonNull(random);
        Map<AuthKey, String> mappings = new HashMap<>();
        for (var mapping : properties.quotaMappings()) {
            mappings.put(new AuthKey(mapping.tenantId(), mapping.authProfileId()), mapping.group());
        }
        sharedQuotas = Map.copyOf(mappings);
        circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenPermits())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .maxWaitDurationInHalfOpenState(Duration.ZERO)
                // Slow successful responses are not a failure sample in this service's contract.
                .slowCallDurationThreshold(FOREVER)
                .writableStackTraceEnabled(false)
                .clock(monotonicClock(monotonic))
                .build();
        Gauge.builder("notification.protection.entries", this, gate -> gate.circuitEntryCount())
                .tag("kind", "CIRCUIT").register(metrics);
        Gauge.builder("notification.protection.entries", this, gate -> gate.quotaEntryCount())
                .tag("kind", "QUOTA").register(metrics);
        for (String state : List.of("CLOSED", "OPEN", "HALF_OPEN")) {
            Gauge.builder("notification.protection.circuits", this, gate -> gate.stateCount(state))
                    .tag("state", state).register(metrics);
        }
    }

    /** Reserve both bounded state entries before T2, so a later 429 always has a pinned quota slot. */
    public Admission acquire(Lease lease) {
        synchronized (lock) {
            long now = monotonic.getAsLong();
            CircuitKey circuitKey = circuitKey(lease);
            QuotaKey quotaKey = quotaKey(lease);
            QuotaEntry quota = quotas.get(quotaKey);
            Duration cooling = remaining(quota, now);
            if (positive(cooling)) return denied(cooling, "QUOTA_COOLDOWN");
            CircuitEntry entry = circuits.get(circuitKey);
            if (entry == null && circuits.size() >= properties.circuitCapacity()
                    || quota == null && quotas.size() >= properties.quotaCapacity()) {
                clean(now);
                entry = circuits.get(circuitKey);
                quota = quotas.get(quotaKey);
                if (entry == null && circuits.size() >= properties.circuitCapacity()
                        || quota == null && quotas.size() >= properties.quotaCapacity()) {
                    return denied(withJitter(properties.retryDelay()), "PROTECTION_CAPACITY");
                }
            }
            if (entry == null) {
                entry = new CircuitEntry(CircuitBreaker.of("notification-delivery", circuitConfig), now);
                circuits.put(circuitKey, entry);
            }
            if (quota == null) {
                quota = new QuotaEntry();
                quotas.put(quotaKey, quota);
            }
            entry.lastUsed = now;
            CircuitBreaker.State previous = entry.breaker.getState();
            boolean acquired = entry.breaker.tryAcquirePermission();
            transition(entry, previous);
            if (!acquired) {
                if (quota.active == 0 && !positive(remaining(quota, now))) quotas.remove(quotaKey, quota);
                boolean open = entry.breaker.getState() == CircuitBreaker.State.OPEN;
                Duration delay = open ? properties.openDuration().minusNanos(Math.max(0, now - entry.openedAt)) : properties.retryDelay();
                if (!positive(delay)) delay = properties.retryDelay();
                return denied(withJitter(delay), open ? "CIRCUIT_OPEN" : "CIRCUIT_HALF_OPEN");
            }
            entry.active++;
            quota.active++;
            metrics.counter("notification.protection.admission", "result", "ALLOWED").increment();
            return new Admission(new Permit(entry, quota, entry.generation), Duration.ZERO, null);
        }
    }

    /** Call while the acquired permit is still live, before complete/close releases its quota pin. */
    public Duration recordQuota429(Lease lease, List<String> retryAfterValues) {
        return recordQuota429(lease, retryAfterValues, wall.instant());
    }

    /** The worker supplies fresh database time so HTTP-date cooling agrees with durable scheduling. */
    public Duration recordQuota429(Lease lease, List<String> retryAfterValues, Instant databaseNow) {
        Objects.requireNonNull(databaseNow);
        synchronized (lock) {
            QuotaEntry entry = quotas.get(quotaKey(lease));
            if (entry == null || entry.active == 0) throw new IllegalStateException("Quota update requires an active admission");
            long now = monotonic.getAsLong();
            Duration delay = Duration.ZERO;
            boolean validRetryAfter = false;
            Instant wallNow = databaseNow;
            boolean forever = entry.forever;
            for (String value : retryAfterValues) {
                Optional<Instant> parsed = RetryAfter.parse(value, wallNow);
                if (parsed.isEmpty()) continue;
                validRetryAfter = true;
                Instant lower = parsed.get();
                if (lower.equals(Instant.MAX)) { forever = true; break; }
                Duration required = Duration.between(wallNow, lower);
                try { required.toNanos(); }
                catch (ArithmeticException excessive) { forever = true; break; }
                if (required.compareTo(delay) > 0) delay = required;
            }
            if (!validRetryAfter) delay = properties.quotaCooldown();
            Duration existing = remaining(entry, now);
            if (existing.compareTo(delay) > 0) delay = existing;
            entry.forever = forever;
            entry.started = now;
            entry.duration = delay;
            metrics.counter("notification.protection.quota_limited").increment();
            return remaining(entry, now);
        }
    }

    public Duration cooldown(Lease lease) {
        synchronized (lock) { return remaining(quotas.get(quotaKey(lease)), monotonic.getAsLong()); }
    }
    public Optional<CircuitSnapshot> circuit(Lease lease) {
        synchronized (lock) {
            CircuitEntry entry = circuits.get(circuitKey(lease));
            if (entry == null) return Optional.empty();
            var snapshot = entry.breaker.getMetrics();
            return Optional.of(new CircuitSnapshot(entry.breaker.getState().name(), snapshot.getNumberOfBufferedCalls(),
                    snapshot.getNumberOfFailedCalls(), entry.active, entry.generation));
        }
    }
    public int circuitEntryCount() { synchronized (lock) { return circuits.size(); } }
    public int quotaEntryCount() { synchronized (lock) { return quotas.size(); } }
    private long stateCount(String state) {
        synchronized (lock) { return circuits.values().stream().filter(entry -> entry.breaker.getState().name().equals(state)).count(); }
    }

    public final class Permit implements AutoCloseable {
        private final CircuitEntry circuit;
        private final QuotaEntry quota;
        private final long generation;
        private final AtomicBoolean ended = new AtomicBoolean();
        private Permit(CircuitEntry circuit, QuotaEntry quota, long generation) {
            this.circuit = circuit;
            this.quota = quota;
            this.generation = generation;
        }
        public void complete(Integer status, FailureKind failure, Duration elapsed) {
            terminate(this, status, failure, elapsed, true);
        }
        @Override public void close() { terminate(this, null, null, Duration.ZERO, false); }
        @Override public String toString() { return "ProtectionPermit[ended=" + ended.get() + "]"; }
    }

    private void terminate(Permit permit, Integer status, FailureKind failure, Duration elapsed, boolean completed) {
        if (!permit.ended.compareAndSet(false, true)) return;
        synchronized (lock) {
            CircuitEntry entry = permit.circuit;
            try {
                if (permit.generation != entry.generation) {
                    // A former CLOSED call must not record into, or release, a newer HALF_OPEN probe.
                    metrics.counter("notification.protection.sample", "result", "STALE").increment();
                    return;
                }
                CircuitBreaker.State previous = entry.breaker.getState();
                boolean failed = completed && (failure == FailureKind.NETWORK || failure == FailureKind.TIMEOUT
                        || failure == null && status != null && status >= 500 && status <= 599);
                boolean succeeded = completed && failure == null && status != null && status >= 200 && status <= 299;
                if (failed) entry.breaker.onError(nanos(elapsed), TimeUnit.NANOSECONDS, BUSINESS_FAILURE);
                else if (succeeded) entry.breaker.onSuccess(nanos(elapsed), TimeUnit.NANOSECONDS);
                else entry.breaker.releasePermission();
                transition(entry, previous);
                metrics.counter("notification.protection.sample", "result", failed ? "FAILURE" : succeeded ? "SUCCESS" : "IGNORED").increment();
            } finally {
                entry.active--;
                permit.quota.active--;
                entry.lastUsed = monotonic.getAsLong();
            }
        }
    }

    private void transition(CircuitEntry entry, CircuitBreaker.State previous) {
        CircuitBreaker.State current = entry.breaker.getState();
        if (current != previous) {
            entry.generation++;
            if (current == CircuitBreaker.State.OPEN) entry.openedAt = monotonic.getAsLong();
            metrics.counter("notification.protection.transition", "from", previous.name(), "to", current.name()).increment();
        }
    }
    private Admission denied(Duration delay, String reason) {
        metrics.counter("notification.protection.admission", "result", reason).increment();
        return new Admission(null, delay, reason);
    }
    private Duration withJitter(Duration delay) {
        double fraction = random.getAsDouble();
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) fraction = 1;
        return delay.plusNanos((long) (properties.jitter().toNanos() * fraction));
    }
    private static Duration remaining(QuotaEntry entry, long now) {
        if (entry == null) return Duration.ZERO;
        if (entry.forever) return FOREVER;
        long remaining = entry.duration.toNanos() - Math.max(0, now - entry.started);
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
    private void clean(long now) {
        quotas.values().removeIf(entry -> entry.active == 0 && !positive(remaining(entry, now)));
        // Long-idle domains may rebuild their sample window; this state is deliberately local,
        // and an OPEN wait or any outstanding permit must never be bypassed by reclamation.
        circuits.values().removeIf(entry -> entry.active == 0
                && now - entry.lastUsed >= properties.circuitIdleTtl().toNanos()
                && (entry.breaker.getState() != CircuitBreaker.State.OPEN
                    || now - entry.openedAt >= properties.openDuration().toNanos()));
    }
    private CircuitKey circuitKey(Lease lease) { return new CircuitKey(lease.providerId(), lease.endpointGroup()); }
    private QuotaKey quotaKey(Lease lease) {
        String shared = sharedQuotas.get(new AuthKey(lease.tenantId(), lease.authProfileId()));
        return shared == null ? new QuotaKey(environment, lease.tenantId(), lease.authProfileId(), null)
                : new QuotaKey(environment, null, null, shared);
    }
    private static long nanos(Duration elapsed) {
        if (elapsed == null || elapsed.isNegative()) return 0;
        try { return elapsed.toNanos(); } catch (ArithmeticException excessive) { return Long.MAX_VALUE; }
    }
    private static boolean positive(Duration duration) { return !duration.isZero() && !duration.isNegative(); }
    private static Clock monotonicClock(LongSupplier monotonic) {
        long origin = monotonic.getAsLong();
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                try { return Instant.EPOCH.plusNanos(monotonic.getAsLong() - origin); }
                catch (DateTimeException excessive) { return Instant.MAX; }
            }
        };
    }
}
