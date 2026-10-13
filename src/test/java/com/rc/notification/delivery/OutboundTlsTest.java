package com.rc.notification.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.auth.ApiKeyAuthStrategy;
import com.rc.notification.auth.OAuthTokenClient;
import com.rc.notification.auth.TokenFailure;
import com.rc.notification.auth.TokenRequest;
import com.rc.notification.security.OutboundPolicy;
import com.rc.notification.support.TlsHttpMock;
import com.rc.notification.support.TlsHttpMock.CertificateKind;
import com.rc.notification.support.TransportEvidence;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

/** SEC-04: real TLS on both production paths, with one trusted CA and normal hostname verification. */
@Timeout(15)
class OutboundTlsTest {
    @TempDir static Path temporary;
    private static TlsHttpMock.Certificates certificates;
    @BeforeAll @Timeout(120) static void createCertificates() throws Exception {
        certificates = new TlsHttpMock.Certificates(temporary.resolve("certificates"));
    }
    @AfterAll static void removeCertificates() throws Exception { if (certificates != null) certificates.close(); }
    static Stream<Arguments> certificatesAndPaths() {
        return Arrays.stream(CertificateKind.values()).flatMap(kind -> Stream.of(false, true).map(token -> Arguments.of(kind, token)));
    }

    @ParameterizedTest @MethodSource("certificatesAndPaths")
    void validatesTheRealCertificateChainValidityAndOriginalHostnameForBusinessAndToken(CertificateKind kind, boolean token) throws Exception {
        if (kind == CertificateKind.EXPIRED) assertThrows(CertificateExpiredException.class, () -> certificates.leaf(kind).checkValidity());
        else certificates.leaf(kind).checkValidity();
        try (var server = new TlsHttpMock(certificates, kind); var healthy = new TlsHttpMock(certificates, CertificateKind.VALID)) {
            var policy = new OutboundPolicy(new OutboundPolicy.Properties(Set.of(TlsHttpMock.HOST), Set.of(server.uri("/").getPort(), healthy.uri("/").getPort()),
                    Set.of(), Set.of(), Set.of("127.0.0.1/32")), new MockEnvironment().withProperty("app.environment", "test"));
            var resolutions = new AtomicInteger();
            DnsResolver resolver = new DnsResolver() {
                @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                    assertEquals(TlsHttpMock.HOST, host); resolutions.incrementAndGet();
                    return new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
                }
                @Override public String resolveCanonicalHostname(String host) { return host; }
            };
            try (var client = new ControlledHttpClient(resolver, 1, policy::allowsConnectionAddress, certificates.clientContext())) {
                byte[] body = "{\"event\":\"tls-validation\"}".getBytes(StandardCharsets.UTF_8);
                var snapshot = new RequestSnapshot(server.uri("/business").toString(), "POST", Map.of(), "application/json", body);
                var sender = new BusinessHttpSender(client, policy);
                var oauth = new OAuthTokenClient(client, policy);
                var request = new TokenRequest(server.uri("/token").toString(), "cp08-client", "cp08-fake-secret", "client_secret_basic", List.of(), null);
                long start = System.nanoTime();
                if (kind == CertificateKind.VALID) {
                    if (token) assertEquals("cp08-fake-token", oauth.exchange(request, Duration.ofSeconds(4)).accessToken());
                    else assertTrue(sender.send(snapshot, new ApiKeyAuthStrategy.Prepared(Map.of(), "fixture-v1"), Duration.ofSeconds(4)).acknowledged());
                    assertEquals(1, server.journal().size());
                    var received = server.journal().get(0);
                    assertEquals(TlsHttpMock.HOST + ":" + server.uri("/").getPort(), received.host());
                    assertEquals(List.of(TlsHttpMock.HOST), received.sni(), "DNS pinning must not replace SNI with the loopback address");
                    if (!token) assertArrayEquals(body, received.body());
                } else {
                    if (token) assertEquals("TOKEN_NETWORK", assertThrows(TokenFailure.class,
                            () -> oauth.exchange(request, Duration.ofSeconds(4))).code());
                    else assertEquals(BusinessHttpSender.FailureKind.NETWORK, assertThrows(BusinessHttpSender.SendFailure.class,
                            () -> sender.send(snapshot, new ApiKeyAuthStrategy.Prepared(Map.of(), "fixture-v1"), Duration.ofSeconds(4))).kind());
                    assertEquals(0, server.journal().size(), "Rejected TLS must not expose HTTP headers, credentials or Body to the peer");
                }
                long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                assertTrue(elapsed < 5000);
                assertEquals(1, resolutions.get(), "Certificate checks retain the validated original authority without a second DNS lookup");
                var probe = new RequestSnapshot(healthy.uri("/business").toString(), "POST", Map.of(), "application/json", new byte[0]);
                assertTrue(sender.send(probe, new ApiKeyAuthStrategy.Prepared(Map.of(), "fixture-v1"), Duration.ofSeconds(4)).acknowledged());
                assertEquals(1, healthy.journal().size()); assertEquals(2, resolutions.get());
                TransportEvidence.write("cp08-tls-" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-" + (token ? "token" : "business"),
                        Map.of("certificateKind", kind.name(), "path", token ? "token" : "business", "budgetMillis", 4000,
                                "elapsedMillis", elapsed, "dnsResolutionsIncludingHealthyProbe", resolutions.get(),
                                "httpRequests", server.journal().size(), "certificateAccepted", kind == CertificateKind.VALID,
                                "originalHostAndSniVerified", kind == CertificateKind.VALID, "sameClientCapacityReusable", true,
                                "healthyTlsProbeRequests", healthy.journal().size()));
            }
        }
    }
}
