package com.rc.notification.security;

import com.rc.notification.api.ApiException;
import java.net.InetAddress;
import java.net.IDN;
import java.net.URI;
import java.util.List;
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
                             Set<String> developmentHttpHosts, Set<Integer> developmentHttpPorts,
                             Set<String> developmentAllowedCidrs) {
        public Properties(Set<String> allowedHosts, Set<Integer> allowedPorts,
                          Set<String> developmentHttpHosts, Set<Integer> developmentHttpPorts) {
            this(allowedHosts, allowedPorts, developmentHttpHosts, developmentHttpPorts, Set.of());
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Properties {
            allowedHosts = allowedHosts == null ? Set.of() : normalize(allowedHosts);
            allowedPorts = allowedPorts == null ? Set.of(443) : Set.copyOf(allowedPorts);
            developmentHttpHosts = developmentHttpHosts == null ? Set.of() : normalize(developmentHttpHosts);
            developmentHttpPorts = developmentHttpPorts == null ? Set.of() : Set.copyOf(developmentHttpPorts);
            developmentAllowedCidrs = developmentAllowedCidrs == null ? Set.of() : Set.copyOf(developmentAllowedCidrs);
            java.util.stream.Stream.concat(allowedPorts.stream(), developmentHttpPorts.stream()).forEach(port -> {
                if (port == null || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid outbound port");
            });
            developmentAllowedCidrs.forEach(Cidr::parse);
        }
        private static Set<String> normalize(Set<String> hosts) {
            return hosts.stream().map(OutboundPolicy::normalizeHost).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
    private final Properties properties;
    private final boolean development;
    private final List<Cidr> developmentCidrs;
    public OutboundPolicy(Properties properties, Environment environment) {
        this.properties = properties;
        String name = environment.getProperty("app.environment", "production");
        if (!Set.of("production", "development", "test").contains(name)) throw new IllegalArgumentException("Invalid application environment");
        development = !name.equals("production");
        developmentCidrs = properties.developmentAllowedCidrs().stream().map(Cidr::parse).toList();
        if (!development && (!properties.developmentHttpHosts().isEmpty() || !properties.developmentHttpPorts().isEmpty()
                || !developmentCidrs.isEmpty())) {
            throw new IllegalArgumentException("Production forbids development HTTP exceptions");
        }
    }
    public void validateConfigurationUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getRawAuthority().endsWith(":")) throw invalid();
            host = normalizeHost(host);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if ("https".equals(scheme)) {
                int port = uri.getPort() < 0 ? 443 : uri.getPort();
                if (!properties.allowedHosts().contains(host) || !properties.allowedPorts().contains(port)) throw invalid();
            } else if ("http".equals(scheme) && development) {
                int port = uri.getPort() < 0 ? 80 : uri.getPort();
                if (!properties.developmentHttpHosts().contains(host) || !properties.developmentHttpPorts().contains(port)) throw invalid();
            } else throw invalid();
            InetAddress literal = IpLiteral.parse(host);
            if (literal != null && !allowedAddress(literal)) throw invalid();
        } catch (ApiException failure) { throw failure; }
        catch (RuntimeException malformed) { throw invalid(); }
    }

    /** Called on the actual DNS answer; it neither resolves a name nor substitutes another address. */
    public boolean allowsConnectionAddress(URI target, InetAddress address) {
        try { validateConfigurationUrl(target.toString()); }
        catch (RuntimeException rejected) { return false; }
        return allowedAddress(address);
    }

    private boolean allowedAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] raw = address.getAddress();
        boolean uniqueLocal = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
        boolean local = address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocal;
        if (local) {
            // validateConfigurationUrl already requires the exact approved authority. The CIDR
            // additionally confines its actual DNS result; HTTPS keeps all certificate checks.
            return development && developmentCidrs.stream().anyMatch(cidr -> cidr.contains(raw));
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
                    || a == 168 && b == 63 && c == 129 && (raw[3] & 0xff) == 16
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
    private static String normalizeHost(String value) {
        if (value == null || value.isBlank() || value.endsWith(".")) throw new IllegalArgumentException("Invalid outbound host");
        String host = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        if (IpLiteral.parse(host) != null) return host.toLowerCase(Locale.ROOT);
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.isEmpty() || ascii.endsWith(".") || ascii.length() > 253) throw new IllegalArgumentException("Invalid outbound host");
        return ascii;
    }
    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                if (parts.length != 2 || !parts[1].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                InetAddress address = IpLiteral.parse(parts[0]);
                if (address == null) throw new IllegalArgumentException();
                byte[] network = address.getAddress();
                int prefix = Integer.parseInt(parts[1]);
                if (prefix > network.length * 8) throw new IllegalArgumentException();
                for (int bit = prefix; bit < network.length * 8; bit++) {
                    if ((network[bit / 8] & (1 << (7 - bit % 8))) != 0) throw new IllegalArgumentException();
                }
                return new Cidr(network, prefix);
            } catch (RuntimeException malformed) { throw new IllegalArgumentException("Invalid development address CIDR"); }
        }
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
            }
            return true;
        }
    }
    private static ApiException invalid() {
        return new ApiException(400, "INVALID_CONFIGURATION", "Target URL is outside the configured outbound policy");
    }
}
