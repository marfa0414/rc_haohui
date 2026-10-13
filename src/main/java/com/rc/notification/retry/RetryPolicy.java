package com.rc.notification.retry;

import com.rc.notification.delivery.BusinessHttpSender.FailureKind;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Pure classification and scheduling arithmetic. Ownership and remaining budgets belong to PostgreSQL. */
@Component
@EnableConfigurationProperties(RetryProperties.class)
public class RetryPolicy {
    public enum Outcome { SUCCESS, RETRY, FAIL }

    private final long baseNanos;
    private final long maximumNanos;
    private final DoubleSupplier random;

    @Autowired
    public RetryPolicy(RetryProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryPolicy(Duration baseDelay, Duration maxDelay, DoubleSupplier random) {
        this(new RetryProperties(baseDelay, maxDelay), random);
    }

    public RetryPolicy(RetryProperties properties, DoubleSupplier random) {
        this.baseNanos = properties.baseDelay().toNanos();
        this.maximumNanos = properties.maxDelay().toNanos();
        this.random = Objects.requireNonNull(random, "random");
    }

    public Outcome classify(Integer httpStatus, FailureKind failure) {
        if (httpStatus != null && failure != null) throw new IllegalArgumentException("A result cannot be both HTTP and transport failure");
        if (httpStatus != null) {
            if (httpStatus >= 200 && httpStatus < 300) return Outcome.SUCCESS;
            return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 && httpStatus < 600
                    ? Outcome.RETRY : Outcome.FAIL;
        }
        // Capacity is transient; cancellation is intercepted by the worker before completion.
        return failure == FailureKind.NETWORK || failure == FailureKind.TIMEOUT || failure == FailureKind.CAPACITY
                ? Outcome.RETRY : Outcome.FAIL;
    }

    public Duration backoff(int attemptNo) {
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo must be positive");
        long ceiling = baseNanos;
        int remainingDoublings = attemptNo - 1;
        // At most 63 iterations even when attemptNo is Integer.MAX_VALUE.
        while (remainingDoublings-- > 0 && ceiling < maximumNanos) {
            ceiling = ceiling >= maximumNanos - ceiling ? maximumNanos : ceiling * 2;
        }
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) throw new IllegalArgumentException("Random sample must be in [0,1]");
        long nanos = Math.min(ceiling, (long) (ceiling * sample));
        return Duration.ofNanos(nanos);
    }

    public Instant nextAttemptAt(Instant now, int attemptNo, Map<String, List<String>> responseHeaders) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(responseHeaders, "responseHeaders");
        Instant next = plusSaturated(now, backoff(attemptNo));
        for (var header : responseHeaders.entrySet()) {
            if (!"retry-after".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            // Duplicate physical fields are not comma-split: commas also occur inside HTTP dates.
            // Respect the strongest valid lower bound rather than risk an early retry.
            for (String value : header.getValue()) {
                Instant lowerBound = RetryAfter.parse(value, now).orElse(now);
                if (lowerBound.isAfter(next)) next = lowerBound;
            }
        }
        return next;
    }

    private static Instant plusSaturated(Instant instant, Duration delay) {
        try { return instant.plus(delay); }
        catch (DateTimeException | ArithmeticException overflow) { return Instant.MAX; }
    }
}
