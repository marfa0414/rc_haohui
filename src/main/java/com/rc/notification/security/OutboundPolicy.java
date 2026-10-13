package com.rc.notification.security;

import com.rc.notification.api.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The same policy governs offline configuration and the addresses used by the real connector. */
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

    /** Called on the actual DNS answer; it neither resolves a name nor substitutes another address. */
    public boolean allowsConnectionAddress(URI target, InetAddress address) {
        try { validateConfigurationUrl(target.toString()); }
        catch (RuntimeException rejected) { return false; }
        if (address == null || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] raw = address.getAddress();
        boolean uniqueLocal = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
        boolean local = address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocal;
        if (local) {
            // Only the exact, explicitly approved development HTTP authority may reach a local mock.
            int port = target.getPort() < 0 ? 80 : target.getPort();
            return development && "http".equals(target.getScheme())
                    && properties.developmentHttpHosts().contains(target.getHost().toLowerCase(Locale.ROOT))
                    && properties.developmentHttpPorts().contains(port);
        }
        return publicAddress(raw);
    }

    /**
     * Conservative Internet destination policy. Special-use/tunnel ranges are intentionally rejected.
     * Registry references: https://www.iana.org/assignments/iana-ipv4-special-registry/
     * and https://www.iana.org/assignments/iana-ipv6-special-registry/ .
     */
    private static boolean publicAddress(byte[] raw) {
        int a = raw[0] & 0xff, b = raw[1] & 0xff;
        if (raw.length == 4) {
            int c = raw[2] & 0xff;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113);
        }
        if (raw.length != 16 || (a & 0xe0) != 0x20) return false;
        int c = raw[2] & 0xff, d = raw[3] & 0xff;
        return !(a == 0x20 && b == 1 && ((c & 0xfe) == 0 || c == 0x0d && d == 0xb8)
                || a == 0x20 && b == 2
                || a == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }
    private static ApiException invalid() {
        return new ApiException(400, "INVALID_CONFIGURATION", "Target URL is outside the configured outbound policy");
    }
}
