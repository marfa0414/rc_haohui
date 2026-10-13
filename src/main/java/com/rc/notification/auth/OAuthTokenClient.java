package com.rc.notification.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import com.rc.notification.delivery.ControlledHttpClient;
import com.rc.notification.delivery.TargetAddressRejectedException;
import com.rc.notification.runtime.RoleConfiguration;
import com.rc.notification.security.OutboundPolicy;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** A single protected OAuth client_credentials exchange, without caching, refresh or implicit retries. */
@Component
@Conditional(RoleConfiguration.WorkerEnabled.class)
public class OAuthTokenClient {
    public static final int MAX_RESPONSE_BYTES = 65536;
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_LIFETIME_SECONDS = Long.MAX_VALUE / 1_000_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxNumberLength(100).maxStringLength(MAX_RESPONSE_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ControlledHttpClient client;
    private final OutboundPolicy outbound;

    public OAuthTokenClient(@Qualifier("tokenHttpClient") ControlledHttpClient client, OutboundPolicy outbound) {
        this.client = client;
        this.outbound = outbound;
    }

    public TokenResult exchange(TokenRequest request, Duration totalBudget) throws TokenFailure {
        long started = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) throw failure("TOKEN_CANCELLED", true);
        if (totalBudget == null || totalBudget.isZero() || totalBudget.isNegative()) throw failure("TOKEN_TIMEOUT", true);
        Duration allowed = totalBudget.compareTo(MAX_TIMEOUT) < 0 ? totalBudget : MAX_TIMEOUT;
        URI target;
        byte[] body;
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            outbound.validateConfigurationUrl(request.tokenUrl());
            target = URI.create(request.tokenUrl());
            validText(request.clientId());
            validText(request.clientSecret());
            if (request.fallbackLifetimeSeconds() != null && !validLifetime(request.fallbackLifetimeSeconds())) {
                throw new IllegalArgumentException("Invalid configured lifetime");
            }
            if (!Set.of("client_secret_basic", "client_secret_post").contains(request.clientAuthenticationMethod())) {
                throw new IllegalArgumentException("Unsupported client authentication method");
            }
            TreeSet<String> scopes = new TreeSet<>();
            for (String scope : request.scopes()) {
                if (!scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")) throw new IllegalArgumentException("Invalid scope");
                scopes.add(scope);
            }
            String form = "grant_type=client_credentials";
            if (!scopes.isEmpty()) form += "&scope=" + encode(String.join(" ", scopes));
            String id = encode(request.clientId()), secret = encode(request.clientSecret());
            if (request.clientAuthenticationMethod().equals("client_secret_basic")) {
                // RFC 6749 2.3.1: encode each component first, then Basic's ASCII id:password pair.
                String authorization = "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.US_ASCII));
                if (authorization.length() + "Authorization".length() + 4 > 16384) throw new IllegalArgumentException("Credential header exceeds byte limit");
                headers.put("Authorization", authorization);
            } else form += "&client_id=" + id + "&client_secret=" + secret;
            body = form.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Token request exceeds byte limit");
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            headers.put("Accept", "application/json");
        } catch (ApiException rejected) {
            throw failure("TOKEN_TARGET_REJECTED", false);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw failure("TOKEN_CONFIGURATION_ERROR", false);
        }
        long remaining = allowed.toNanos() - (System.nanoTime() - started);
        if (remaining <= 0) throw failure("TOKEN_TIMEOUT", true);
        try {
            Decoded decoded = client.executeWithBody(target, "POST", headers, body, Duration.ofNanos(remaining), MAX_RESPONSE_BYTES,
                    status -> status >= 200 && status < 300 || status >= 400 && status < 500 && status != 408 && status != 429,
                    (response, bytes) -> decode(response, bytes, request.fallbackLifetimeSeconds()));
            if (decoded.failure != null) throw decoded.failure;
            return decoded.result;
        } catch (TimeoutException | SocketTimeoutException | ConnectionRequestTimeoutException timeout) {
            throw failure("TOKEN_TIMEOUT", true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure("TOKEN_CANCELLED", true);
        } catch (RejectedExecutionException capacity) {
            throw failure("TOKEN_CAPACITY", true);
        } catch (TargetAddressRejectedException rejected) {
            throw failure("TOKEN_TARGET_REJECTED", false);
        } catch (ControlledHttpClient.ResponseTooLargeException excessive) {
            throw failure("TOKEN_RESPONSE_TOO_LARGE", false);
        } catch (ClientProtocolException protocol) {
            throw failure("TOKEN_PROTOCOL_ERROR", false);
        } catch (IOException network) {
            throw failure("TOKEN_NETWORK", true);
        }
    }

    private static Decoded decode(ControlledHttpClient.Response response, byte[] bytes, Long fallback) {
        int status = response.statusCode();
        List<String> retryAfter = response.headers().getOrDefault("retry-after", List.of());
        if (status == 429) return error("TOKEN_RATE_LIMITED", true, retryAfter);
        if (status >= 500 && status < 600) return error("TOKEN_SERVER_ERROR", true, retryAfter);
        if (status == 408) return error("TOKEN_TIMEOUT", true, retryAfter);
        if (status < 200 || status >= 300 && (status < 400 || status >= 500)) return error("TOKEN_HTTP_REJECTED", false, List.of());
        JsonNode json;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            json = JSON.readTree(text);
        } catch (IOException | RuntimeException malformed) { return error("TOKEN_PROTOCOL_ERROR", false, List.of()); }
        if (json == null || !json.isObject()) return error("TOKEN_PROTOCOL_ERROR", false, List.of());
        if (json.has("error")) {
            String code = switch (json.path("error").asText()) {
                case "invalid_client" -> "TOKEN_INVALID_CLIENT";
                case "unauthorized_client" -> "TOKEN_UNAUTHORIZED_CLIENT";
                case "invalid_scope" -> "TOKEN_INVALID_SCOPE";
                case "invalid_request" -> "TOKEN_INVALID_REQUEST";
                case "unsupported_grant_type" -> "TOKEN_UNSUPPORTED_GRANT_TYPE";
                default -> "TOKEN_HTTP_REJECTED";
            };
            return error(code, false, List.of());
        }
        if (status >= 300) return error("TOKEN_HTTP_REJECTED", false, List.of());
        if (!json.path("token_type").isTextual() || !json.path("token_type").textValue().equalsIgnoreCase("Bearer")
                || !json.path("access_token").isTextual()) return error("TOKEN_PROTOCOL_ERROR", false, List.of());
        String token = json.path("access_token").textValue();
        // RFC 6750 b64token is opaque, not necessarily a JWT. Its alphabet also forbids header injection.
        if (token.length() > 16360 || !token.matches("[A-Za-z0-9._~+/-]+=*")) return error("TOKEN_PROTOCOL_ERROR", false, List.of());
        Long seconds = fallback;
        if (json.has("expires_in")) {
            JsonNode value = json.get("expires_in");
            if (!value.isIntegralNumber() || !value.canConvertToLong()) return error("TOKEN_PROTOCOL_ERROR", false, List.of());
            seconds = value.longValue();
        }
        if (seconds == null || !validLifetime(seconds)) return error("TOKEN_PROTOCOL_ERROR", false, List.of());
        return new Decoded(new TokenResult(token, Duration.ofSeconds(seconds)), null);
    }

    private static boolean validLifetime(long seconds) { return seconds > 0 && seconds <= MAX_LIFETIME_SECONDS; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void validText(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Invalid client credentials");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw new IllegalArgumentException("Invalid credential text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid credential text");
        }
    }
    private static TokenFailure failure(String code, boolean retryable) { return new TokenFailure(code, retryable, List.of()); }
    private static Decoded error(String code, boolean retryable, List<String> retryAfter) {
        return new Decoded(null, new TokenFailure(code, retryable, retryAfter));
    }
    private record Decoded(TokenResult result, TokenFailure failure) {
        @Override public String toString() { return "Decoded[REDACTED]"; }
    }
}
