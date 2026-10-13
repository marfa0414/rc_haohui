package com.rc.notification.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(Integer globalConcurrency, Duration leaseDuration, Duration attemptTimeout,
                               Duration businessTimeout, Duration pollInterval, Boolean schedulingEnabled,
                               Duration shutdownGracePeriod) {
    public WorkerProperties {
        globalConcurrency = globalConcurrency == null ? 10 : globalConcurrency;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(90) : leaseDuration;
        attemptTimeout = attemptTimeout == null ? Duration.ofSeconds(30) : attemptTimeout;
        businessTimeout = businessTimeout == null ? Duration.ofSeconds(10) : businessTimeout;
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        schedulingEnabled = schedulingEnabled == null || schedulingEnabled;
        shutdownGracePeriod = shutdownGracePeriod == null ? Duration.ofSeconds(30) : shutdownGracePeriod;
        if (globalConcurrency < 1 || globalConcurrency > 1000 || !positive(leaseDuration) || !positive(attemptTimeout)
                || !positive(businessTimeout) || !positive(pollInterval) || shutdownGracePeriod.isNegative()
                || leaseDuration.compareTo(attemptTimeout) <= 0 || attemptTimeout.compareTo(businessTimeout) <= 0
                || businessTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Worker concurrency and time budgets are invalid");
        }
    }
    private static boolean positive(Duration value) { return !value.isZero() && !value.isNegative(); }
}
