package com.rc.notification.auth;

import java.util.UUID;

public record PreparedAuthentication(ApiKeyAuthStrategy.Prepared transport, TokenKey tokenKey,
                                     UUID generation, byte[] tokenDigest, CachedToken cachedToken) {
    public PreparedAuthentication(ApiKeyAuthStrategy.Prepared transport, TokenKey tokenKey, UUID generation, byte[] tokenDigest) {
        this(transport, tokenKey, generation, tokenDigest, null);
    }
    public PreparedAuthentication { tokenDigest = tokenDigest == null ? null : tokenDigest.clone(); }
    @Override public byte[] tokenDigest() { return tokenDigest == null ? null : tokenDigest.clone(); }
    public boolean oauth() { return tokenKey != null; }
    @Override public String toString() { return "PreparedAuthentication[credentials=REDACTED]"; }
}
