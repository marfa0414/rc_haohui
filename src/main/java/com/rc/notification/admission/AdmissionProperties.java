package com.rc.notification.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admission")
public record AdmissionProperties(Double requestsPerSecond, Integer burstCapacity, Long maxBacklog) {
    public AdmissionProperties {
        requestsPerSecond = requestsPerSecond == null ? 100.0 : requestsPerSecond;
        burstCapacity = burstCapacity == null ? 200 : burstCapacity;
        maxBacklog = maxBacklog == null ? 10000L : maxBacklog;
        if (!Double.isFinite(requestsPerSecond) || requestsPerSecond <= 0 || requestsPerSecond > 1_000_000
                || burstCapacity < 1 || burstCapacity > 1_000_000 || maxBacklog < 1 || maxBacklog > 10_000_000) {
            throw new IllegalArgumentException("Invalid admission capacity");
        }
    }
}
