package com.rc.notification.auth;

import java.time.Duration;

public record TokenResult(String accessToken, Duration lifetime) {
    @Override public String toString() { return "TokenResult[REDACTED]"; }
}
