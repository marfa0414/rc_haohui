package com.rc.notification.auth;

import org.springframework.stereotype.Component;

@Component
public class EnvironmentSecretResolver implements SecretResolver {
    @Override public ResolvedSecret resolve(String reference) {
        if (reference == null || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsupported secret reference");
        String name = reference.substring(4);
        String value = System.getenv(name);
        String version = credentialVersion(reference);
        if (value == null || value.isEmpty() || version == null || !version.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalStateException("Credential and credential version must both be supplied");
        }
        return new ResolvedSecret(value, version);
    }
    @Override public String credentialVersion(String reference) {
        if (reference == null || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) return null;
        String version = System.getenv(reference.substring(4) + "_VERSION");
        return version != null && version.matches("[A-Za-z0-9._:-]{1,128}") ? version : null;
    }
}
