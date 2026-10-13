package com.rc.notification.security;

import com.rc.notification.api.ApiException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Offline policy only. Connection-time address enforcement is added at the CP08 boundary. */
@Component
@EnableConfigurationProperties(OutboundPolicy.Properties.class)
public class OutboundPolicy {
    @ConfigurationProperties(prefix = "app.outbound")
    public record Properties(Set<String> allowedHosts, Set<Integer> allowedPorts,
                             Set<String> developmentHttpHosts, Set<Integer> developmentHttpPorts) {
        public Properties {
            allowedHosts = allowedHosts == null ? Set.of() : normalize(allowedHosts);
            allowedPorts = allowedPorts == null ? Set.of(443) : Set.copyOf(allowedPorts);
            developmentHttpHosts = developmentHttpHosts == null ? Set.of() : normalize(developmentHttpHosts);
            developmentHttpPorts = developmentHttpPorts == null ? Set.of() : Set.copyOf(developmentHttpPorts);
        }
        private static Set<String> normalize(Set<String> hosts) {
            return hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
    private final Properties properties;
    private final boolean development;
    public OutboundPolicy(Properties properties, Environment environment) {
        this.properties = properties;
        String name = environment.getProperty("app.environment", "production");
        if (!Set.of("production", "development", "test").contains(name)) throw new IllegalArgumentException("Invalid application environment");
        development = !name.equals("production");
        if (!development && (!properties.developmentHttpHosts().isEmpty() || !properties.developmentHttpPorts().isEmpty())) {
            throw new IllegalArgumentException("Production forbids development HTTP exceptions");
        }
    }
    public void validateConfigurationUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawFragment() != null || host.endsWith(".")) throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            if ("https".equals(uri.getScheme())) {
                int port = uri.getPort() < 0 ? 443 : uri.getPort();
                if (!properties.allowedHosts().contains(host) || !properties.allowedPorts().contains(port)) throw invalid();
            } else if ("http".equals(uri.getScheme()) && development) {
                int port = uri.getPort() < 0 ? 80 : uri.getPort();
                if (!properties.developmentHttpHosts().contains(host) || !properties.developmentHttpPorts().contains(port)) throw invalid();
            } else throw invalid();
        } catch (ApiException failure) { throw failure; }
        catch (RuntimeException malformed) { throw invalid(); }
    }
    private static ApiException invalid() {
        return new ApiException(400, "INVALID_CONFIGURATION", "Target URL is outside the configured outbound policy");
    }
}
