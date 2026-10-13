package com.rc.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Process-local telemetry. No tenant, event, URL or credential ever becomes a meter label. */
@Component
public final class NotificationMetrics {
    private final Counter accepted;
    private final Counter deduplicated;
    private final Map<String, Counter> responses = new HashMap<>();
    private final Map<String, Timer> durations = new HashMap<>();

    public NotificationMetrics(MeterRegistry registry) {
        // Diagnostic wall-clock calibration only; task eligibility and leases still use PostgreSQL time.
        Gauge.builder("notification.clock.epoch.seconds", () -> Instant.now().toEpochMilli() / 1000.0).register(registry);
        accepted = registry.counter("notification.submission", "result", "ACCEPTED");
        deduplicated = registry.counter("notification.submission", "result", "DEDUPLICATED");
        for (String result : List.of("ACCEPTED", "DEDUPLICATED", "INVALID_REQUEST", "UNAUTHENTICATED", "FORBIDDEN",
                "NOT_FOUND", "CONFLICT", "PAYLOAD_TOO_LARGE", "RATE_LIMITED", "UNAVAILABLE", "ERROR")) {
            responses.put(result, registry.counter("notification.submission.http", "result", result));
            durations.put(result, Timer.builder("notification.submission.http.duration").tag("result", result).register(registry));
        }
    }

    /** Invoked only after the admission transaction has successfully committed. */
    public void committed(boolean duplicate) {
        if (duplicate) deduplicated.increment(); else accepted.increment();
    }

    void response(int status, long elapsedNanos) {
        String result = switch (status) {
            case 202 -> "ACCEPTED";
            case 200 -> "DEDUPLICATED";
            case 400, 415, 422 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 429 -> "RATE_LIMITED";
            case 503 -> "UNAVAILABLE";
            default -> "ERROR";
        };
        responses.get(result).increment();
        durations.get(result).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }
}
