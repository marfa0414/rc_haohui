package com.rc.notification.protection;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.protection")
public record ProtectionProperties(Integer slidingWindowSize, Integer minimumCalls, Float failureRateThreshold,
                                   Duration openDuration, Integer halfOpenPermits, Duration retryDelay,
                                   Duration jitter, Integer circuitCapacity, Integer quotaCapacity,
                                   Duration circuitIdleTtl, Duration quotaCooldown, List<QuotaMapping> quotaMappings) {
    public ProtectionProperties {
        slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
        minimumCalls = minimumCalls == null ? 10 : minimumCalls;
        failureRateThreshold = failureRateThreshold == null ? 50.0f : failureRateThreshold;
        openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        halfOpenPermits = halfOpenPermits == null ? 1 : halfOpenPermits;
        retryDelay = retryDelay == null ? Duration.ofSeconds(1) : retryDelay;
        jitter = jitter == null ? Duration.ofSeconds(1) : jitter;
        circuitCapacity = circuitCapacity == null ? 1000 : circuitCapacity;
        quotaCapacity = quotaCapacity == null ? 1000 : quotaCapacity;
        circuitIdleTtl = circuitIdleTtl == null ? Duration.ofMinutes(30) : circuitIdleTtl;
        quotaCooldown = quotaCooldown == null ? Duration.ofSeconds(30) : quotaCooldown;
        quotaMappings = quotaMappings == null ? List.of() : List.copyOf(quotaMappings);
        if (slidingWindowSize < 1 || slidingWindowSize > 10000 || minimumCalls < 1 || minimumCalls > slidingWindowSize
                || !Float.isFinite(failureRateThreshold) || failureRateThreshold <= 0 || failureRateThreshold > 100
                || halfOpenPermits < 1 || halfOpenPermits > 1000 || circuitCapacity < 1 || circuitCapacity > 100000
                || quotaCapacity < 1 || quotaCapacity > 100000 || quotaMappings.size() > 100000
                || !bounded(openDuration, Duration.ofHours(1), false) || openDuration.toMillis() < 1
                || !bounded(retryDelay, Duration.ofMinutes(1), false)
                || !bounded(jitter, Duration.ofMinutes(1), true)
                || !bounded(circuitIdleTtl, Duration.ofDays(30), false)
                || !bounded(quotaCooldown, Duration.ofHours(1), false)) {
            throw new IllegalArgumentException("Invalid protection limits");
        }
        var contexts = new HashSet<Context>();
        for (QuotaMapping mapping : quotaMappings) {
            if (!contexts.add(new Context(mapping.tenantId(), mapping.authProfileId()))) {
                throw new IllegalArgumentException("Duplicate quota mapping");
            }
        }
    }

    private static boolean bounded(Duration value, Duration maximum, boolean zeroAllowed) {
        return !value.isNegative() && (zeroAllowed || !value.isZero()) && value.compareTo(maximum) <= 0;
    }
    private record Context(String tenant, UUID profile) { }

    /** Matching contexts share a quota only within the current deployment environment. */
    public record QuotaMapping(String tenantId, UUID authProfileId, String group) {
        public QuotaMapping {
            if (tenantId == null || !tenantId.matches("[A-Za-z0-9._:-]{1,128}") || authProfileId == null
                    || group == null || !group.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("Invalid quota mapping");
            }
        }
    }
}
