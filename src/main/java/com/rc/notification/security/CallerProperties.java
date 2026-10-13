package com.rc.notification.security;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record CallerProperties(List<Caller> callers) {
    public CallerProperties { callers = callers == null ? List.of() : List.copyOf(callers); }
    public record Caller(String id, String token, Set<String> roles, Set<String> tenants, Set<String> operations) {
        public Caller {
            if (id == null || id.isBlank() || token == null || token.isBlank()
                    || roles == null || roles.isEmpty() || tenants == null || operations == null) {
                throw new IllegalArgumentException("Every caller requires an identity, credential and explicit permissions");
            }
            if (!Set.of("BUSINESS", "CONFIG_ADMIN", "OPERATOR", "ADMIN").containsAll(roles)) {
                throw new IllegalArgumentException("Unsupported caller role");
            }
            roles = Set.copyOf(roles);
            tenants = Set.copyOf(tenants);
            operations = Set.copyOf(operations);
        }
        public CallerPrincipal principal() { return new CallerPrincipal(id, roles, tenants, operations); }
        @Override public String toString() { return "Caller[id=" + id + ", credential=REDACTED]"; }
    }
}
