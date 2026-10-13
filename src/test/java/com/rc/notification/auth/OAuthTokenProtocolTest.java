package com.rc.notification.auth;

import static org.junit.jupiter.api.Assertions.*;
import static com.rc.notification.support.NotificationApiFixture.JSON;

import com.rc.notification.delivery.ControlledHttpClient;
import com.rc.notification.security.OutboundPolicy;
import com.rc.notification.support.HttpMock;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** CP06 TC-AUTH-02/06/09/10/13 real protected HTTP protocol; response Body and credentials remain in memory only. */
@Timeout(20)
class OAuthTokenProtocolTest {
    private static final String SUCCESS = "{\"access_token\":\"opaque-fake-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    @ParameterizedTest @ValueSource(strings = {"client_secret_basic", "client_secret_post"})
    void usesCorrectOAuthClientAuthenticationAndNormalizedScopeEncoding(String method) throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> HttpMock.respond(e, 200, SUCCESS));
            var result = f.client.exchange(f.request(method, null), Duration.ofSeconds(2));
            assertEquals("opaque-fake-token", result.accessToken());
            assertEquals(Duration.ofSeconds(3600), result.lifetime());
            assertEquals(1, f.mock.journal().size());
            var actual = f.mock.journal().get(0);
            assertEquals("POST", actual.method());
            assertTrue(actual.firstHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
            var form = form(actual.body());
            assertEquals("client_credentials", form.get("grant_type"));
            assertEquals("read write", form.get("scope"));
            assertFalse(form.containsKey("refresh_token"));
            if (method.equals("client_secret_basic")) {
                String credentials = URLEncoder.encode("client: +中", StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode("fake secret:+密钥", StandardCharsets.UTF_8);
                assertEquals("Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)), actual.firstHeader("Authorization"));
                assertFalse(form.containsKey("client_id")); assertFalse(form.containsKey("client_secret"));
            } else {
                assertNull(actual.firstHeader("Authorization"));
                assertEquals("client: +中", form.get("client_id"));
                assertEquals("fake secret:+密钥", form.get("client_secret"));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {
        "{}", "{\"access_token\":\"fake\",\"token_type\":\"MAC\",\"expires_in\":3600}",
        "{\"access_token\":\"\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
        "{\"access_token\":\"contains space\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":0}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":-1}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":1.5}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":\"3600\"}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":null}",
        "{\"access_token\":\"fake\",\"token_type\":\"Bearer\",\"expires_in\":9223372036854775807}",
        "not-json", "[]", "{} {}"})
    void malformedProtocolResponsesFailPermanentlyAndCannotBeHiddenByFallbackTtl(String response) throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> HttpMock.respond(e, 200, response));
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_post", 3600L), Duration.ofSeconds(2)));
            assertEquals("TOKEN_PROTOCOL_ERROR", failure.code()); assertFalse(failure.retryable());
            assertFalse(failure.toString().contains("fake secret"));
            assertEquals(1, f.mock.journal().size(), "A deterministic malformed response must not cause a hidden fetch loop");
        }
    }

    @Test
    void missingExpiryRequiresAnExplicitLifetimeAndDoesNotAssumeTokensAreJwt() throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> HttpMock.respond(e, 200, "{\"access_token\":\"not-a-jwt\",\"token_type\":\"Bearer\"}"));
            assertEquals("TOKEN_PROTOCOL_ERROR", assertThrows(TokenFailure.class,
                    () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(2))).code());
            var token = f.client.exchange(f.request("client_secret_basic", 123L), Duration.ofSeconds(2));
            assertEquals("not-a-jwt", token.accessToken()); assertEquals(Duration.ofSeconds(123), token.lifetime());
            assertEquals(2, f.mock.journal().size());
        }
    }

    @ParameterizedTest @CsvSource({"400,invalid_client,TOKEN_INVALID_CLIENT,false", "401,unauthorized_client,TOKEN_UNAUTHORIZED_CLIENT,false",
            "400,invalid_scope,TOKEN_INVALID_SCOPE,false", "403,unknown,TOKEN_HTTP_REJECTED,false",
            "429,unknown,TOKEN_RATE_LIMITED,true", "500,unknown,TOKEN_SERVER_ERROR,true", "503,unknown,TOKEN_SERVER_ERROR,true"})
    void oauthErrorResponsesHaveExplicitSanitizedRetryClassification(int status, String error, String code, boolean retryable) throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> {
                e.getResponseHeaders().set("Retry-After", "2");
                HttpMock.respond(e, status, "{\"error\":\"" + error + "\",\"error_description\":\"sensitive-error-description\"}");
            });
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(2)));
            assertEquals(code, failure.code()); assertEquals(retryable, failure.retryable());
            assertFalse(failure.toString().contains("sensitive-error-description"));
            if (status == 429) assertEquals(List.of("2"), failure.retryAfterValues());
            assertEquals(1, f.mock.journal().size());
        }
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 307, 308})
    void tokenRedirectsNeverForwardClientCredentials(int status) throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> {
                e.getResponseHeaders().set("Location", f.mock.uri("/forbidden").toString()); HttpMock.respond(e, status, "redirect");
            });
            f.mock.route("/forbidden", e -> HttpMock.respond(e, 200, SUCCESS));
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_post", null), Duration.ofSeconds(2)));
            assertFalse(failure.retryable()); assertEquals(0, f.mock.count("/forbidden")); assertEquals(1, f.mock.count("/token"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {65536, 65537})
    void tokenJsonHasAnExactUtf8ByteLimit(int bytes) throws Exception {
        try (var f = new ProtocolFixture()) {
            String prefix = SUCCESS.substring(0, SUCCESS.length() - 1) + ",\"padding\":\"";
            String body = prefix + "x".repeat(bytes - prefix.getBytes(StandardCharsets.UTF_8).length - 2) + "\"}";
            assertEquals(bytes, body.getBytes(StandardCharsets.UTF_8).length);
            f.mock.route("/token", e -> HttpMock.respond(e, 200, body));
            if (bytes == 65536) assertEquals("opaque-fake-token", f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(2)).accessToken());
            else {
                var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(2)));
                assertEquals("TOKEN_RESPONSE_TOO_LARGE", failure.code()); assertFalse(failure.retryable());
            }
        }
    }

    @Test
    void aLongRequestedBudgetIsStillCappedAtFiveSecondsAndDoesNotRetry() throws Exception {
        var release = new CountDownLatch(1);
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> {
                try { release.await(8, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            long start = System.nanoTime();
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(20)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertEquals("TOKEN_TIMEOUT", failure.code()); assertTrue(failure.retryable());
            assertTrue(elapsed.compareTo(Duration.ofSeconds(6)) < 0, "Token call must obey its five-second total cap");
            assertEquals(1, f.mock.journal().size());
        } finally { release.countDown(); }
    }

    @Test
    void slowResponseBodyConsumesTheSameDeadlineAndCapacityIsReusableAfterCancellation() throws Exception {
        var release = new CountDownLatch(1);
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> {
                e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush();
                try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            f.mock.route("/healthy", e -> HttpMock.respond(e, 200, SUCCESS));
            long start = System.nanoTime();
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofMillis(250)));
            assertEquals("TOKEN_TIMEOUT", failure.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0);
            release.countDown();
            var request = new TokenRequest(f.mock.uri("/healthy").toString(), "client", "fake", "client_secret_basic", List.of(), null);
            assertEquals("opaque-fake-token", f.client.exchange(request, Duration.ofSeconds(2)).accessToken());
        } finally { release.countDown(); }
    }

    @Test
    void chunkedTokenBodyWithoutContentLengthCannotExceedTheByteLimit() throws Exception {
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> {
                e.sendResponseHeaders(200, 0);
                try (var out = e.getResponseBody()) { out.write("x".repeat(65537).getBytes(StandardCharsets.US_ASCII)); }
                finally { e.close(); }
            });
            var failure = assertThrows(TokenFailure.class, () -> f.client.exchange(f.request("client_secret_basic", null), Duration.ofSeconds(2)));
            assertEquals("TOKEN_RESPONSE_TOO_LARGE", failure.code()); assertFalse(failure.retryable());
            assertEquals(1, f.mock.journal().size());
        }
    }

    @Test
    void responseDecoderWorkIsAlsoInsideTheTransportDeadline() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var f = new ProtocolFixture()) {
            f.mock.route("/token", e -> HttpMock.respond(e, 200, SUCCESS));
            long start = System.nanoTime();
            assertThrows(java.util.concurrent.TimeoutException.class, () -> f.transport.executeWithBody(
                    f.mock.uri("/token"), "POST", Map.of(), new byte[0], Duration.ofMillis(250), 65536, status -> true,
                    (head, bytes) -> {
                        entered.countDown();
                        try { new CountDownLatch(1).await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return "must-not-escape-after-deadline";
                    }));
            assertEquals(0, entered.getCount());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "Cancellation must reach parsing work too");
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0);
        }
    }

    static Map<String, String> form(byte[] bytes) {
        var result = new LinkedHashMap<String, String>();
        for (String pair : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] parts = pair.split("=", 2);
            assertNull(result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8)));
        }
        return result;
    }

    static final class ProtocolFixture implements AutoCloseable {
        final HttpMock mock = new HttpMock();
        final ControlledHttpClient transport;
        final OAuthTokenClient client;
        ProtocolFixture() throws Exception {
            var policy = new OutboundPolicy(new OutboundPolicy.Properties(Set.of("127.0.0.1"), Set.of(443), Set.of("127.0.0.1"),
                    Set.of(mock.uri("/token").getPort()), Set.of("127.0.0.1/32")), new MockEnvironment().withProperty("app.environment", "test"));
            transport = new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, 2, policy::allowsConnectionAddress);
            client = new OAuthTokenClient(transport, policy);
        }
        TokenRequest request(String method, Long fallback) {
            return new TokenRequest(mock.uri("/token").toString(), "client: +中", "fake secret:+密钥", method, List.of("write", "read", "read"), fallback);
        }
        @Override public void close() throws java.io.IOException { try { transport.close(); } finally { mock.close(); } }
    }
}
