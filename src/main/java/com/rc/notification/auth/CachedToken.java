package com.rc.notification.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record CachedToken(String accessToken, Duration lifetime, Instant receivedAt, Instant expiresAt,
                          UUID generation, byte[] digest, long startedNanos) {
    public CachedToken { digest = digest.clone(); }
    @Override public byte[] digest() { return digest.clone(); }
    @Override public String toString() { return "CachedToken[generation=" + generation + ",token=REDACTED,digest=REDACTED]"; }
}
