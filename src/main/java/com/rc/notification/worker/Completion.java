package com.rc.notification.worker;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

public record Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt,
                         boolean retryable, List<String> retryAfterValues, boolean authRecovery, byte[] rejectedTokenDigest,
                         Duration retryDelay) {
    public Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt) {
        this(outcome, httpStatus, errorCode, httpSendStartedAt, false, List.of());
    }
    public Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt,
                      boolean retryable, List<String> retryAfterValues) {
        this(outcome, httpStatus, errorCode, httpSendStartedAt, retryable, retryAfterValues, false, null);
    }
    public Completion(String outcome, Integer httpStatus, String errorCode, Instant httpSendStartedAt,
                      boolean retryable, List<String> retryAfterValues, boolean authRecovery, byte[] rejectedTokenDigest) {
        this(outcome, httpStatus, errorCode, httpSendStartedAt, retryable, retryAfterValues, authRecovery, rejectedTokenDigest, Duration.ZERO);
    }
    public Completion {
        if (!java.util.Set.of("NOT_SENT", "HTTP_ACKNOWLEDGED", "HTTP_REJECTED", "UNKNOWN").contains(outcome)) {
            throw new IllegalArgumentException("Invalid delivery outcome");
        }
        retryAfterValues = List.copyOf(retryAfterValues);
        if (retryDelay == null || retryDelay.isNegative()) throw new IllegalArgumentException("Invalid retry delay");
        if (authRecovery && (rejectedTokenDigest == null || rejectedTokenDigest.length != 32)) throw new IllegalArgumentException("Missing rejected credential fingerprint");
        rejectedTokenDigest = rejectedTokenDigest == null ? null : rejectedTokenDigest.clone();
    }
    @Override public byte[] rejectedTokenDigest() { return rejectedTokenDigest == null ? null : rejectedTokenDigest.clone(); }
    @Override public String toString() {
        return "Completion[outcome=" + outcome + ",httpStatus=" + httpStatus + ",errorCode=" + errorCode
                + ",retryable=" + retryable + ",retryAfterValues=REDACTED]";
    }
}
