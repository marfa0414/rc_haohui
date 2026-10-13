package com.rc.notification.auth;

import com.rc.notification.runtime.RoleConfiguration;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class AuthStrategyRegistry {
    private final Map<String, NotificationAuthStrategy> strategies;
    private final TokenCache tokens;
    public AuthStrategyRegistry(List<NotificationAuthStrategy> strategies, TokenCache tokens) {
        Map<String, NotificationAuthStrategy> byName = new HashMap<>();
        for (var strategy : strategies) if (byName.putIfAbsent(strategy.type(), strategy) != null) throw new IllegalStateException("Duplicate authentication strategy");
        this.strategies = Map.copyOf(byName);
        this.tokens = tokens;
    }
    public AuthCooldown cooldown(AuthContext context) {
        var strategy = strategies.get(context.profile().path("type").asText());
        return strategy == null ? AuthCooldown.none() : strategy.cooldown(context);
    }
    public PreparedAuthentication prepare(AuthContext context, Duration remaining, Duration planned, byte[] rejected)
            throws TokenFailure, InterruptedException {
        var strategy = strategies.get(context.profile().path("type").asText());
        if (strategy == null) throw new TokenFailure("AUTH_TYPE_UNAVAILABLE", false, List.of());
        return strategy.prepare(context, remaining, planned, rejected);
    }
    public void invalidate(PreparedAuthentication authentication) {
        if (authentication.oauth()) tokens.invalidate(authentication.tokenKey(), authentication.generation());
    }
    public void ensureValidForSend(PreparedAuthentication authentication, Duration planned) throws TokenFailure {
        if (authentication.oauth() && (authentication.cachedToken() == null || !tokens.usable(authentication.cachedToken(), planned))) {
            throw new TokenFailure("TOKEN_EXPIRED_BEFORE_SEND", true, List.of());
        }
    }
}
