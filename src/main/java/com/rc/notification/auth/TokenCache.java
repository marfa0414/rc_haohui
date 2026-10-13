package com.rc.notification.auth;

import com.rc.notification.retry.RetryAfter;
import com.rc.notification.runtime.RoleConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Bounded process-local singleflight. Network access and waiter blocking never hold the cache monitor. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class TokenCache implements AutoCloseable {
    private final OAuthTokenClient client;
    private final AuthProperties properties;
    private final LongSupplier monotonic;
    private final Clock clock;
    private final DoubleSupplier random;
    private final MeterRegistry metrics;
    private final Map<TokenKey, Entry> entries = new HashMap<>();
    private final ThreadPoolExecutor executor;
    private boolean closed;

    private static final class Entry {
        CachedToken token;
        CompletableFuture<CachedToken> inFlight;
        TokenFailure failure;
        long cooldownStarted;
        Duration cooldown = Duration.ZERO;
        boolean forever;
    }

    @Autowired public TokenCache(OAuthTokenClient client, AuthProperties properties, MeterRegistry metrics) {
        this(client, properties, System::nanoTime, Clock.systemUTC(), () -> ThreadLocalRandom.current().nextDouble(), metrics);
    }

    public TokenCache(OAuthTokenClient client, AuthProperties properties, LongSupplier monotonic,
                      Clock clock, DoubleSupplier random, MeterRegistry metrics) {
        this.client = client;
        this.properties = properties;
        this.monotonic = monotonic;
        this.clock = clock;
        this.random = random;
        this.metrics = metrics;
        executor = new ThreadPoolExecutor(properties.httpConcurrency(), properties.httpConcurrency(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), work -> { Thread thread = new Thread(work, "oauth-token-leader"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public CachedToken get(TokenKey key, TokenRequest request, Duration waitBudget, Duration plannedBusinessTime, byte[] rejectedDigest)
            throws TokenFailure, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Token waiter interrupted");
        Duration wait = waitBudget.compareTo(properties.tokenTimeout()) < 0 ? waitBudget : properties.tokenTimeout();
        if (wait.isZero() || wait.isNegative()) throw failure("TOKEN_TIMEOUT", true);
        long waitingStarted = monotonic.getAsLong();
        CompletableFuture<CachedToken> future;
        Entry entry;
        boolean leader = false;
        synchronized (entries) {
            if (closed) throw failure("TOKEN_CANCELLED", true);
            long now = monotonic.getAsLong();
            entry = entries.get(key);
            if (entry == null) {
                clean(now);
                if (entries.size() >= properties.cacheCapacity()) throw failure("TOKEN_CAPACITY", true);
                entry = new Entry();
                entries.put(key, entry);
            }
            if (entry.token != null && usable(entry.token, now, plannedBusinessTime)
                    && !sameDigest(entry.token, rejectedDigest)) {
                metrics.counter("notification.oauth.cache", "result", "HIT").increment();
                return entry.token;
            }
            if (entry.failure != null && positive(cooldown(entry, now))) throw entry.failure;
            future = entry.inFlight;
            if (future == null) {
                future = new CompletableFuture<>();
                entry.inFlight = future;
                leader = true;
            }
        }
        if (leader) launch(entry, future, request);
        else metrics.counter("notification.oauth.cache", "result", "WAIT").increment();
        long remaining = wait.toNanos() - (monotonic.getAsLong() - waitingStarted);
        if (remaining <= 0) throw failure("TOKEN_TIMEOUT", true);
        try {
            CachedToken token = future.get(remaining, TimeUnit.NANOSECONDS);
            if (sameDigest(token, rejectedDigest)) throw failure("AUTH_REFRESH_REJECTED", false);
            if (!usable(token, monotonic.getAsLong(), plannedBusinessTime)) throw failure("TOKEN_LIFETIME_INCOMPATIBLE", false);
            return token;
        } catch (TimeoutException timeout) { throw failure("TOKEN_TIMEOUT", true); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof TokenFailure controlled) throw controlled;
            throw failure("TOKEN_CONFIGURATION_ERROR", false);
        }
    }

    private void launch(Entry entry, CompletableFuture<CachedToken> future, TokenRequest request) {
        long started = monotonic.getAsLong();
        Instant wallStarted = clock.instant();
        try {
            executor.execute(() -> {
                CachedToken result = null;
                TokenFailure error = null;
                try {
                    Duration budget = properties.tokenTimeout().minusNanos(monotonic.getAsLong() - started);
                    if (budget.isNegative() || budget.isZero()) throw failure("TOKEN_TIMEOUT", true);
                    metrics.counter("notification.oauth.exchange").increment();
                    TokenResult received = client.exchange(request, budget);
                    long finished = monotonic.getAsLong();
                    if (finished - started >= properties.tokenTimeout().toNanos()) throw failure("TOKEN_TIMEOUT", true);
                    result = new CachedToken(received.accessToken(), received.lifetime(), clock.instant(),
                            plus(wallStarted, received.lifetime()), UUID.randomUUID(), digest(received.accessToken()), started);
                } catch (TokenFailure known) { error = known; }
                catch (RuntimeException invalid) { error = failure("TOKEN_CONFIGURATION_ERROR", false); }
                publish(entry, future, result, error);
            });
        } catch (RejectedExecutionException capacity) { publish(entry, future, null, failure("TOKEN_CAPACITY", true)); }
    }

    private void publish(Entry entry, CompletableFuture<CachedToken> future, CachedToken result, TokenFailure failure) {
        synchronized (entries) {
            if (entry.inFlight != future) return;
            entry.inFlight = null;
            if (closed) { future.completeExceptionally(failure("TOKEN_CANCELLED", true)); return; }
            if (failure == null) {
                entry.token = result;
                entry.failure = null;
                entry.cooldown = Duration.ZERO;
                entry.forever = false;
                future.complete(result);
            } else {
                entry.failure = failure;
                entry.cooldownStarted = monotonic.getAsLong();
                double fraction = random.getAsDouble();
                if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) fraction = 1;
                long range = properties.cooldownMax().toNanos() - properties.cooldownMin().toNanos();
                entry.cooldown = properties.cooldownMin().plusNanos((long) (range * fraction));
                Instant now = clock.instant();
                for (String value : failure.retryAfterValues()) {
                    Instant lower = RetryAfter.parse(value, now).orElse(now);
                    if (lower.equals(Instant.MAX)) { entry.forever = true; break; }
                    Duration required = Duration.between(now, lower);
                    try { required.toNanos(); }
                    catch (ArithmeticException excessive) { entry.forever = true; break; }
                    if (required.compareTo(entry.cooldown) > 0) entry.cooldown = required;
                }
                metrics.counter("notification.oauth.failure", "code", failure.code()).increment();
                future.completeExceptionally(failure);
            }
        }
    }

    public boolean invalidate(TokenKey key, UUID generation) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null || entry.token == null || !entry.token.generation().equals(generation)) return false;
            entry.token = null;
            return true;
        }
    }
    public Duration cooldownRemaining(TokenKey key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            return entry == null || entry.failure == null || !entry.failure.retryable() ? Duration.ZERO : cooldown(entry, monotonic.getAsLong());
        }
    }
    public List<String> cooldownRetryAfter(TokenKey key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            return entry == null || entry.failure == null ? List.of() : entry.failure.retryAfterValues();
        }
    }
    public int size() { synchronized (entries) { return entries.size(); } }
    public int inFlightCount() { synchronized (entries) { return (int) entries.values().stream().filter(entry -> entry.inFlight != null).count(); } }
    public int activeLeaderCount() { return executor.getActiveCount(); }
    private void clean(long now) {
        entries.values().removeIf(entry -> entry.inFlight == null && !positive(cooldown(entry, now))
                && (entry.token == null || elapsed(entry.token, now) >= entry.token.lifetime().toNanos()));
    }
    private Duration cooldown(Entry entry, long now) {
        if (entry.failure == null) return Duration.ZERO;
        if (entry.forever) return Duration.ofNanos(Long.MAX_VALUE);
        long remaining = entry.cooldown.toNanos() - (now - entry.cooldownStarted);
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
    private static boolean positive(Duration value) { return !value.isZero() && !value.isNegative(); }
    public boolean usable(CachedToken token, Duration planned) {
        return usable(token, monotonic.getAsLong(), planned);
    }
    private boolean usable(CachedToken token, long now, Duration planned) {
        long life = token.lifetime().toNanos();
        long window = Math.min(life / 10, Duration.ofSeconds(60).toNanos());
        window = Math.max(window, planned.plus(properties.safetyMargin()).toNanos());
        return life - elapsed(token, now) > window;
    }
    private static long elapsed(CachedToken token, long now) { return now - token.startedNanos(); }
    private static boolean sameDigest(CachedToken token, byte[] rejected) { return rejected != null && MessageDigest.isEqual(token.digest(), rejected); }
    private static TokenFailure failure(String code, boolean retryable) { return new TokenFailure(code, retryable, List.of()); }
    private static Instant plus(Instant at, Duration duration) { try { return at.plus(duration); } catch (DateTimeException invalid) { return Instant.MAX; } }
    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public void close() {
        synchronized (entries) {
            closed = true;
            for (Entry entry : entries.values()) if (entry.inFlight != null) entry.inFlight.completeExceptionally(failure("TOKEN_CANCELLED", true));
            entries.clear();
        }
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
