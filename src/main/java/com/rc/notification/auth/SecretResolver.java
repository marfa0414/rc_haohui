package com.rc.notification.auth;

public interface SecretResolver {
    ResolvedSecret resolve(String reference);
    /** Optional, non-secret admission metadata. Unknown metadata skips pre-reservation cooling checks. */
    default String credentialVersion(String reference) { return null; }
    record ResolvedSecret(String value, String credentialVersion) {
        @Override public String toString() { return "ResolvedSecret[REDACTED]"; }
    }
}
