package com.rc.notification.auth;

import com.rc.notification.runtime.RoleConfiguration;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class ApiKeyNotificationAuthStrategy implements NotificationAuthStrategy {
    private final ApiKeyAuthStrategy apiKeys;
    public ApiKeyNotificationAuthStrategy(ApiKeyAuthStrategy apiKeys) { this.apiKeys = apiKeys; }
    @Override public String type() { return "api_key"; }
    @Override public PreparedAuthentication prepare(AuthContext context, Duration remaining, Duration planned, byte[] rejected) throws TokenFailure {
        try {
            if (!context.tenantId().equals(context.profile().path("tenantId").asText())) throw new IllegalArgumentException("Authentication tenant mismatch");
            return new PreparedAuthentication(apiKeys.prepare(context.tenantId(), context.profile(), context.profile().path("secretRef").asText()), null, null, null);
        }
        catch (IllegalArgumentException | IllegalStateException invalid) { throw new TokenFailure("AUTH_CONFIGURATION_ERROR", false, List.of()); }
    }
}
