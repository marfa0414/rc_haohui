package com.rc.notification.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyAuthStrategy {
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding",
            "content-type", "proxy-authorization", "te", "trailer", "upgrade", "expect", "proxy-connection", "keep-alive");
    private final SecretResolver secrets;
    public ApiKeyAuthStrategy(SecretResolver secrets) { this.secrets = secrets; }
    public record Prepared(Map<String, String> headers, String credentialVersion) {
        public Prepared { headers = Map.copyOf(headers); }
        @Override public String toString() { return "Prepared[headers=REDACTED,credentialVersion=" + credentialVersion + "]"; }
    }
    public Prepared prepare(JsonNode parameters, String secretReference) {
        validateParameters(parameters);
        SecretResolver.ResolvedSecret secret = secrets.resolve(secretReference);
        String name = parameters.path("headerName").asText();
        String value = parameters.path("prefix").asText("") + secret.value();
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || invalidControl(value)
                || name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length + 4 > 16384) {
            throw new IllegalArgumentException("Credential header cannot be safely prepared");
        }
        return new Prepared(Map.of(name, value), secret.credentialVersion());
    }

    public static void validateParameters(JsonNode parameters) {
        if (!parameters.path("headerName").isTextual()) throw new IllegalArgumentException("Invalid credential header");
        String name = parameters.path("headerName").textValue();
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid credential header");
        }
        if (parameters.has("prefix") && (!parameters.get("prefix").isTextual()
                || invalidControl(parameters.get("prefix").textValue()))) {
            throw new IllegalArgumentException("Invalid credential prefix");
        }
        String prefix = parameters.path("prefix").asText("");
        if (name.getBytes(StandardCharsets.UTF_8).length + prefix.getBytes(StandardCharsets.UTF_8).length + 4 > 16384) {
            throw new IllegalArgumentException("Credential header exceeds byte limit");
        }
    }
    private static boolean invalidControl(String value) {
        return value.chars().anyMatch(character -> character == 127 || character < 32 && character != '\t');
    }
}
