package com.rc.notification.auth;

import java.util.List;

public record TokenRequest(String tokenUrl, String clientId, String clientSecret, String clientAuthenticationMethod,
                           List<String> scopes, Long fallbackLifetimeSeconds) {
    public TokenRequest { scopes = scopes == null ? List.of() : List.copyOf(scopes); }
    @Override public String toString() { return "TokenRequest[REDACTED]"; }
}
