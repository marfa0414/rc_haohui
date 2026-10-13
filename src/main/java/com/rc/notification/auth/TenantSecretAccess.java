package com.rc.notification.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-owned permissions, independent of tenant-editable authentication profiles. */
@Component
@EnableConfigurationProperties(TenantSecretAccess.Properties.class)
public class TenantSecretAccess {
    @ConfigurationProperties("app.secrets")
    public record Properties(Map<String, Set<String>> tenantReferences) {
        public Properties {
            Map<String, Set<String>> checked = new LinkedHashMap<>();
            if (tenantReferences != null) tenantReferences.forEach((tenant, references) -> {
                if (tenant == null || !tenant.matches("[A-Za-z0-9._:-]{1,64}") || references == null) {
                    throw new IllegalArgumentException("Invalid tenant secret permission");
                }
                for (String reference : references) {
                    if (reference == null || reference.length() > 256 || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException("Invalid tenant secret permission");
                    }
                }
                checked.put(tenant, Set.copyOf(references));
            });
            tenantReferences = Map.copyOf(checked);
        }
    }

    public static final class ForbiddenReference extends IllegalArgumentException {
        public ForbiddenReference() { super("Secret reference is not permitted for this tenant"); }
    }

    private final SecretResolver resolver;
    private final Properties properties;
    public TenantSecretAccess(SecretResolver resolver, Properties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }
    public void requireAllowed(String tenant, String reference) {
        if (tenant == null || reference == null || !properties.tenantReferences().getOrDefault(tenant, Set.of()).contains(reference)) {
            throw new ForbiddenReference();
        }
    }
    public SecretResolver.ResolvedSecret resolve(String tenant, String reference) {
        requireAllowed(tenant, reference);
        return resolver.resolve(reference);
    }
    public String credentialVersion(String tenant, String reference) {
        requireAllowed(tenant, reference);
        return resolver.credentialVersion(reference);
    }
}
