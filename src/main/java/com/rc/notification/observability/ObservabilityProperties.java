package com.rc.notification.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.observability")
public record ObservabilityProperties(Boolean samplingEnabled, Duration sampleInterval) {
    public ObservabilityProperties {
        samplingEnabled = samplingEnabled == null ? true : samplingEnabled;
        sampleInterval = sampleInterval == null ? Duration.ofSeconds(5) : sampleInterval;
        if (sampleInterval.compareTo(Duration.ofSeconds(1)) < 0 || sampleInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Metric sampling interval must be between one second and one minute");
        }
    }
}
