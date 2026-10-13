package com.rc.notification.worker;

import java.time.Instant;
import java.util.List;

public record Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt,
                         boolean retryable, List<String> retryAfterValues) {
    public Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt) {
        this(outcome, httpStatus, errorCode, httpSendStartedAt, false, List.of());
    }
    public Completion {
        if (!java.util.Set.of("NOT_SENT", "HTTP_ACKNOWLEDGED", "HTTP_REJECTED", "UNKNOWN").contains(outcome)) {
            throw new IllegalArgumentException("Invalid delivery outcome");
        }
        retryAfterValues = List.copyOf(retryAfterValues);
    }
    @Override public String toString() {
        return "Completion[outcome=" + outcome + ",httpStatus=" + httpStatus + ",errorCode=" + errorCode
                + ",retryable=" + retryable + ",retryAfterValues=REDACTED]";
    }
}
