package com.rc.notification.auth;

import com.rc.notification.runtime.RoleConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class OAuthNotificationAuthStrategy implements NotificationAuthStrategy {
    private final SecretResolver secrets;
    private final TokenCache tokens;
    public OAuthNotificationAuthStrategy(SecretResolver secrets, TokenCache tokens) { this.secrets = secrets; this.tokens = tokens; }
    @Override public String type() { return "oauth2_client_credentials"; }
    @Override public AuthCooldown cooldown(AuthContext context) {
        try {
            String version = secrets.credentialVersion(context.profile().path("clientSecretRef").asText());
            if (version == null || !version.matches("[A-Za-z0-9._:-]{1,128}")) return AuthCooldown.none();
            TokenKey key = key(context, version);
            return new AuthCooldown(tokens.cooldownRemaining(key), tokens.cooldownRetryAfter(key));
        } catch (RuntimeException unknownMetadata) { return AuthCooldown.none(); }
    }
    @Override public PreparedAuthentication prepare(AuthContext context, Duration remaining, Duration planned, byte[] rejected)
            throws TokenFailure, InterruptedException {
        long started = System.nanoTime();
        SecretResolver.ResolvedSecret secret;
        try { secret = secrets.resolve(context.profile().path("clientSecretRef").asText()); }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw new TokenFailure("AUTH_CONFIGURATION_ERROR", false, List.of()); }
        if (secret.credentialVersion() == null || !secret.credentialVersion().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new TokenFailure("AUTH_CONFIGURATION_ERROR", false, List.of());
        }
        TokenKey key = key(context, secret.credentialVersion());
        var profile = context.profile();
        Long fallback = profile.has("tokenLifetimeSeconds") ? profile.path("tokenLifetimeSeconds").longValue() : null;
        var request = new TokenRequest(key.tokenUrl(), key.clientId(), secret.value(), key.clientAuthMethod(), key.normalizedScopes(), fallback);
        CachedToken token = tokens.get(key, request, remaining.minusNanos(System.nanoTime() - started), planned, rejected);
        return new PreparedAuthentication(new ApiKeyAuthStrategy.Prepared(Map.of("Authorization", "Bearer " + token.accessToken()),
                secret.credentialVersion()), key, token.generation(), token.digest(), token);
    }
    private TokenKey key(AuthContext context, String version) {
        var profile = context.profile();
        List<String> scopes = new ArrayList<>();
        profile.path("scopes").forEach(scope -> scopes.add(scope.asText()));
        return new TokenKey(context.environment(), context.tenantId(), context.profileId(), version,
                profile.path("tokenUrl").asText(), profile.path("clientId").asText(), profile.path("clientAuthenticationMethod").asText(), scopes);
    }
}
