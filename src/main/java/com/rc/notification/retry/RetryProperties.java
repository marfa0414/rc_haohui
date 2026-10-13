package com.rc.notification.retry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retry")
public record RetryProperties(Duration baseDelay, Duration maxDelay) {
    public RetryProperties {
        baseDelay = baseDelay == null ? Duration.ofSeconds(30) : baseDelay;
        maxDelay = maxDelay == null ? Duration.ofHours(1) : maxDelay;
        if (baseDelay.isZero() || baseDelay.isNegative() || maxDelay.isZero() || maxDelay.isNegative()
                || baseDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("Retry delays must be positive and base must not exceed maximum");
        }
        try { baseDelay.toNanos(); maxDelay.toNanos(); }
        catch (ArithmeticException excessive) { throw new IllegalArgumentException("Retry delays exceed the supported range"); }
    }
}
