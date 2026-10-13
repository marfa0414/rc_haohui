package com.rc.notification.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CP03 preparation portion of TC-AUTH-01; actual outbound authentication remains CP04. */
class ApiKeyAuthStrategyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ApiKeyAuthStrategy strategy(SecretResolver resolver) {
        return new ApiKeyAuthStrategy(new TenantSecretAccess(resolver,
                new TenantSecretAccess.Properties(java.util.Map.of("tenant-a", java.util.Set.of("env:TEST_ONLY_SECRET")))));
    }

    @Test
    void resolvesTheCurrentCredentialOnlyDuringPreparationAndRedactsDebugOutput() {
        var lookups = new AtomicInteger();
        var strategy = strategy(reference -> {
            assertEquals("env:TEST_ONLY_SECRET", reference);
            lookups.incrementAndGet();
            return new SecretResolver.ResolvedSecret("test-only-secret-sentinel", "version-7");
        });
        assertEquals(0, lookups.get());
        var prepared = strategy.prepare("tenant-a", JSON.createObjectNode().put("headerName", "X-API-Key").put("prefix", "Key "), "env:TEST_ONLY_SECRET");
        assertEquals("Key test-only-secret-sentinel", prepared.headers().get("X-API-Key"));
        assertEquals("version-7", prepared.credentialVersion());
        assertEquals(1, lookups.get());
        assertFalse(prepared.toString().contains("test-only-secret-sentinel"));
        assertFalse(new SecretResolver.ResolvedSecret("test-only-secret-sentinel", "v1").toString().contains("test-only-secret-sentinel"));
    }

    @ParameterizedTest @ValueSource(strings = {"Host", "Content-Length", "Expect", "Proxy-Connection", "Keep-Alive"})
    void rejectsTransportHeadersBeforeLookingUpAnySecret(String header) {
        var lookups = new AtomicInteger();
        var strategy = strategy(reference -> { lookups.incrementAndGet(); return new SecretResolver.ResolvedSecret("x", "1"); });
        assertThrows(IllegalArgumentException.class,
                () -> strategy.prepare("tenant-a", JSON.createObjectNode().put("headerName", header), "env:TEST_ONLY_SECRET"));
        assertEquals(0, lookups.get());
    }

    @ParameterizedTest @ValueSource(ints = {0, 7, 10, 13, 31, 127})
    void controlCharacterSecretsCannotBecomeHeaders(int control) {
        var strategy = strategy(reference -> new SecretResolver.ResolvedSecret("before" + (char) control + "after", "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> strategy.prepare("tenant-a", JSON.createObjectNode().put("headerName", "X-API-Key"), "env:TEST_ONLY_SECRET"));
    }

    @Test
    void missingDeploymentPermissionRejectsBothValueAndMetadataBeforeSecretResolution() {
        var lookups = new AtomicInteger();
        var access = new TenantSecretAccess(reference -> { lookups.incrementAndGet(); return new SecretResolver.ResolvedSecret("must-not-resolve", "v1"); },
                new TenantSecretAccess.Properties(null));
        assertThrows(TenantSecretAccess.ForbiddenReference.class, () -> access.resolve("tenant-a", "env:TEST_ONLY_SECRET"));
        assertThrows(TenantSecretAccess.ForbiddenReference.class, () -> access.credentialVersion("tenant-a", "env:TEST_ONLY_SECRET"));
        assertEquals(0, lookups.get());
    }

    @Test
    void horizontalTabsRemainAllowedInCredentialHeaderValues() {
        var strategy = strategy(reference -> new SecretResolver.ResolvedSecret("part\tone", "v1"));
        var prepared = strategy.prepare("tenant-a", JSON.createObjectNode().put("headerName", "X-API-Key").put("prefix", "Key\t"),
                "env:TEST_ONLY_SECRET");
        assertEquals("Key\tpart\tone", prepared.headers().get("X-API-Key"));
    }
}
