package com.rc.notification.security;

import com.rc.notification.api.ApiException;
import java.util.Set;

public record CallerPrincipal(String id, Set<String> roles, Set<String> tenants, Set<String> operations) {
    public CallerPrincipal {
        roles = Set.copyOf(roles);
        tenants = Set.copyOf(tenants);
        operations = Set.copyOf(operations);
    }

    public boolean allowsTenant(String tenant) { return tenants.contains("*") || tenants.contains(tenant); }
    public boolean allowsOperation(String operation) { return operations.contains("*") || operations.contains(operation); }
    public boolean hasRole(String role) { return roles.contains("ADMIN") || roles.contains(role); }
    public boolean canRead(String tenant, String operation) {
        return (hasRole("BUSINESS") || hasRole("OPERATOR")) && allowsTenant(tenant) && allowsOperation(operation);
    }
    public void requireSubmission(String tenant, String operation) {
        if (!hasRole("BUSINESS") || !allowsTenant(tenant) || !allowsOperation(operation)) {
            throw new ApiException(403, "FORBIDDEN_OPERATION", "Operation is outside the caller's authorization");
        }
    }
    public void requireQuery(String tenant) {
        if (!(hasRole("BUSINESS") || hasRole("OPERATOR")) || !allowsTenant(tenant)) {
            throw new ApiException(403, "FORBIDDEN_OPERATION", "Query is outside the caller's authorization");
        }
    }
}
