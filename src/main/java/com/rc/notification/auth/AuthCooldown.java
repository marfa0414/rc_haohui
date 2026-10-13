package com.rc.notification.auth;

import java.time.Duration;
import java.util.List;

public record AuthCooldown(Duration remaining, List<String> retryAfterValues) {
    public AuthCooldown { retryAfterValues = List.copyOf(retryAfterValues); }
    public static AuthCooldown none() { return new AuthCooldown(Duration.ZERO, List.of()); }
    @Override public String toString() { return "AuthCooldown[remaining=" + remaining + ",headers=REDACTED]"; }
}
