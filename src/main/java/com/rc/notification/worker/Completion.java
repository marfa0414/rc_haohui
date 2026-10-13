package com.rc.notification.worker;

import java.time.Instant;

public record Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt) {
    public Completion {
        if (!java.util.Set.of("NOT_SENT", "HTTP_ACKNOWLEDGED", "HTTP_REJECTED", "UNKNOWN").contains(outcome)) {
            throw new IllegalArgumentException("Invalid delivery outcome");
        }
    }
}
