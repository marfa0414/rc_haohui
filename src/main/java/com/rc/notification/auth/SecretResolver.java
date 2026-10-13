package com.rc.notification.auth;

public interface SecretResolver {
    ResolvedSecret resolve(String reference);
    record ResolvedSecret(String value, String credentialVersion) {
        @Override public String toString() { return "ResolvedSecret[REDACTED]"; }
    }
}
