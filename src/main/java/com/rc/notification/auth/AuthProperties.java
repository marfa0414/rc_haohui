package com.rc.notification.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.oauth")
public record AuthProperties(Duration tokenTimeout, Duration safetyMargin, Integer cacheCapacity,
                             Integer httpConcurrency, Duration cooldownMin, Duration cooldownMax) {
    public AuthProperties {
        tokenTimeout = tokenTimeout == null ? Duration.ofSeconds(5) : tokenTimeout;
        safetyMargin = safetyMargin == null ? Duration.ofSeconds(2) : safetyMargin;
        cacheCapacity = cacheCapacity == null ? 1000 : cacheCapacity;
        httpConcurrency = httpConcurrency == null ? 10 : httpConcurrency;
        cooldownMin = cooldownMin == null ? Duration.ofSeconds(1) : cooldownMin;
        cooldownMax = cooldownMax == null ? Duration.ofSeconds(5) : cooldownMax;
        if (tokenTimeout.isNegative() || tokenTimeout.isZero() || tokenTimeout.compareTo(Duration.ofSeconds(5)) > 0
                || safetyMargin.isNegative() || safetyMargin.compareTo(Duration.ofMinutes(1)) > 0
                || cacheCapacity < 1 || cacheCapacity > 100000 || httpConcurrency < 1 || httpConcurrency > 1000
                || cooldownMin.isNegative() || cooldownMin.isZero() || cooldownMax.compareTo(cooldownMin) < 0
                || cooldownMax.compareTo(Duration.ofMinutes(1)) > 0) throw new IllegalArgumentException("Invalid OAuth limits");
    }
}
