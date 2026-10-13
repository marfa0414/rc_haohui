package com.rc.notification.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment defaults are copied into each accepted task and each explicitly restarted run. */
@ConfigurationProperties(prefix = "app.delivery")
public record DeliveryProperties(Integer maxAttempts, Duration runDuration) {
    public DeliveryProperties {
        maxAttempts = maxAttempts == null ? 12 : maxAttempts;
        runDuration = runDuration == null ? Duration.ofHours(24) : runDuration;
        if (maxAttempts < 1 || maxAttempts > 1000 || runDuration.compareTo(Duration.ofMillis(1)) < 0
                || runDuration.compareTo(Duration.ofDays(365)) > 0) throw new IllegalArgumentException("Invalid delivery budget");
    }
}
