package com.rc.notification.security;

import static org.junit.jupiter.api.Assertions.*;
import static com.rc.notification.support.NotificationApiFixture.JSON;

import com.rc.notification.api.ApiException;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.auth.ApiKeyAuthStrategy;
import com.rc.notification.auth.OAuthTokenClient;
import com.rc.notification.auth.TokenFailure;
import com.rc.notification.auth.TokenRequest;
import com.rc.notification.delivery.BusinessHttpSender;
import com.rc.notification.delivery.ControlledHttpClient;
import com.rc.notification.delivery.TargetAddressRejectedException;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.RawHttpPeer;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** CP08 TC-SEC-01/02/03/08: actual connector, controlled DNS and explicit zero TCP/HTTP observations. */
@Timeout(30)
class OutboundSecurityTest {
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private static final String HOST = "supplier.invalid";

    @Test
    void productionRejectsEverySpecialUseAddressForBothTargetRolesWithoutDnsOrConnections() throws Exception {
        var policy = policy("production", Set.of(HOST), Set.of(443), Set.of(), Set.of(), Set.of());
        for (String role : List.of("business", "token")) {
            URI target = URI.create("https://" + HOST + "/" + role);
            for (String address : List.of("0.0.0.0", "10.0.0.1", "127.0.0.1", "127.0.0.2", "172.16.0.1", "192.168.1.1",
                    "169.254.169.254", "100.100.100.200", "168.63.129.16", "192.0.0.1", "192.0.2.1", "192.88.99.1",
                    "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
                    "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
                    "64:ff9b::a00:1", "2001:db8::1", "2002:7f00:1::", "3fff::1")) {
                assertFalse(policy.allowsConnectionAddress(target, InetAddress.getByName(address)), role + " must reject " + address);
            }
            assertTrue(policy.allowsConnectionAddress(target, InetAddress.getByName("8.8.8.8")));
            assertTrue(policy.allowsConnectionAddress(target, InetAddress.getByName("2606:4700:4700::1111")));
        }
    }

    @Test
    void authorityNormalizationAndExplicitDevelopmentIntersectionCannotBeBypassed() throws Exception {
        var production = policy("production", Set.of(HOST, "BÜCHER.example", "127.0.0.1", "::1"), Set.of(443), Set.of(), Set.of(), Set.of());
        assertDoesNotThrow(() -> production.validateConfigurationUrl("HTTPS://SUPPLIER.INVALID/notify"));
        assertDoesNotThrow(() -> production.validateConfigurationUrl("https://xn--bcher-kva.example/notify"));
        for (String target : List.of("http://supplier.invalid/", "file:///etc/passwd", "ftp://supplier.invalid/", "https://user:secret@ supplier.invalid/",
                "https://user:secret@supplier.invalid/", "https://supplier.invalid/#fragment", "https://supplier.invalid./",
                "https://supplier.invalid:/", "https://supplier.invalid:444/", "https://supplier.invalid:0/", "https://supplier.invalid:65536/",
                "https://supplier.invalid.evil/", "https://supplier.invalid%2f.evil/", "https://127.0.0.1/", "https://[::1]/",
                "https://2130706433/", "https://127.1/", "https://0177.0.0.1/", "https://0x7f000001/", "https://0x7f.0.0.1/",
                "https://[fe80::1%25lo0]/")) {
            assertThrows(ApiException.class, () -> production.validateConfigurationUrl(target), target);
        }
        for (int badPort : List.of(0, -1, 65536)) assertThrows(IllegalArgumentException.class,
                () -> new OutboundPolicy.Properties(Set.of(HOST), Set.of(badPort), Set.of(), Set.of(), Set.of()));
        for (String badHost : List.of("127.1", "2130706433", "0177.0.0.1", "0x7f000001")) assertThrows(IllegalArgumentException.class,
                () -> new OutboundPolicy.Properties(Set.of(badHost), Set.of(443), Set.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> policy("production", Set.of(HOST), Set.of(443), Set.of(), Set.of(), Set.of("127.0.0.1/32")));
        assertThrows(IllegalArgumentException.class, () -> policy("production", Set.of(HOST), Set.of(443), Set.of(HOST), Set.of(80), Set.of()));
        var noCidr = policy("test", Set.of(HOST), Set.of(443), Set.of(HOST), Set.of(8080), Set.of());
        var restricted = policy("test", Set.of(HOST), Set.of(443), Set.of(HOST), Set.of(8080), Set.of("127.0.0.1/32"));
        URI accepted = URI.create("http://supplier.invalid:8080/notify");
        assertFalse(noCidr.allowsConnectionAddress(accepted, InetAddress.getByName("127.0.0.1")));
        assertTrue(restricted.allowsConnectionAddress(accepted, InetAddress.getByName("127.0.0.1")));
        assertFalse(restricted.allowsConnectionAddress(accepted, InetAddress.getByName("127.0.0.2")));
        assertFalse(restricted.allowsConnectionAddress(URI.create("http://supplier.invalid:8081/"), InetAddress.getByName("127.0.0.1")));
        assertFalse(restricted.allowsConnectionAddress(URI.create("http://other.invalid:8080/"), InetAddress.getByName("127.0.0.1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "token"})
    void publicPreflightCannotAuthorizeAReboundPrivateConnection(String role) throws Exception {
        try (var peer = new RawHttpPeer(socket -> { })) {
            int port = peer.uri("https", "/").getPort();
            URI target = URI.create("https://" + HOST + ":" + port + "/" + role);
            var policy = policy("production", Set.of(HOST), Set.of(port), Set.of(), Set.of(), Set.of());
            assertTrue(policy.allowsConnectionAddress(target, InetAddress.getByName("8.8.8.8")), "A prior public result cannot waive the actual connection check");
            var resolutions = new CopyOnWriteArrayList<String>();
            try (var client = new ControlledHttpClient(dns(resolutions, "127.0.0.1"), 1, policy::allowsConnectionAddress)) {
                assertTargetRejected(role, client, policy, target);
                assertEquals(List.of(HOST + "=127.0.0.1"), resolutions);
                assertEquals(0, peer.accepted.get());
                evidence("rebound-private-" + role, role, resolutions, peer.accepted.get(), 0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "token"})
    void oneForbiddenDnsAnswerRejectsTheWholeSetBeforeAnyConnection(String role) throws Exception {
        try (var peer = new RawHttpPeer(socket -> { })) {
            int port = peer.uri("http", "/").getPort();
            var policy = policy("test", Set.of(HOST), Set.of(443), Set.of(HOST), Set.of(port), Set.of("127.0.0.1/32"));
            for (List<String> answers : List.of(List.of("127.0.0.1", "127.0.0.2"), List.of("127.0.0.1", "::1"), List.of("127.0.0.2", "127.0.0.1"))) {
                var resolutions = new CopyOnWriteArrayList<String>();
                try (var client = new ControlledHttpClient(dns(resolutions, answers.toArray(String[]::new)), 1, policy::allowsConnectionAddress)) {
                    assertTargetRejected(role, client, policy, URI.create("http://" + HOST + ":" + port + "/" + role));
                    assertEquals(1, resolutions.size());
                    assertEquals(0, peer.accepted.get(), "Even the first allowed answer must not connect when another answer is forbidden");
                }
            }
            evidence("mixed-dns-" + role, role, List.of("allowed-and-forbidden-A", "allowed-A-and-forbidden-AAAA", "forbidden-A-first"), peer.accepted.get(), 0);
        }
    }

    @Test
    void numericLiteralCannotBypassTheConnectorAddressPredicate() throws Exception {
        try (var peer = new RawHttpPeer(socket -> { })) {
            URI target = peer.uri("https", "/numeric");
            var policy = policy("production", Set.of("127.0.0.1"), Set.of(target.getPort()), Set.of(), Set.of(), Set.of());
            var resolutions = new CopyOnWriteArrayList<String>();
            try (var client = new ControlledHttpClient(dns(resolutions, "8.8.8.8"), 1, policy::allowsConnectionAddress)) {
                assertThrows(TargetAddressRejectedException.class, () -> client.execute(target, "POST", Map.of(), new byte[0], BUDGET));
                assertEquals(0, peer.accepted.get());
                assertTrue(resolutions.isEmpty(), "Numeric literals are validated directly, without an alternate DNS resolution");
                evidence("numeric-connector", "transport", resolutions, peer.accepted.get(), 0);
            }
        }
    }

    @Test
    void checkedDnsAddressesAreUsedDirectlyAndHostIsPreservedOnEveryNewConnection() throws Exception {
        try (var mock = new HttpMock()) {
            mock.route("/notify", e -> HttpMock.respond(e, 200, "ignored"));
            var target = mock.virtualUri(HOST, "/notify");
            var policy = policy("test", Set.of(HOST), Set.of(443), Set.of(HOST), Set.of(target.getPort()), Set.of("127.0.0.1/32"));
            var resolutions = new CopyOnWriteArrayList<String>();
            var index = new AtomicInteger();
            DnsResolver resolver = new DnsResolver() {
                public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                    String answer = index.getAndIncrement() == 0 ? "127.0.0.1" : "127.0.0.2";
                    resolutions.add(host + "=" + answer); return new InetAddress[] {InetAddress.getByName(answer)};
                }
                public String resolveCanonicalHostname(String host) { throw new AssertionError("No second canonical-hostname resolution is permitted"); }
            };
            try (var client = new ControlledHttpClient(resolver, 1, policy::allowsConnectionAddress)) {
                assertEquals(200, new BusinessHttpSender(client, policy).send(snapshot(target), authentication(), BUDGET).statusCode());
                assertEquals(1, index.get(), "Successful connection must use exactly the validated DNS answer");
                assertEquals(HOST + ":" + target.getPort(), mock.journal().get(0).firstHeader("Host"));
                assertTargetRejected("business", client, policy, target);
                assertEquals(2, index.get(), "A subsequent physical connection must validate the fresh DNS answer");
                assertEquals(1, mock.count("/notify"));
                evidence("dns-used-once", "business", resolutions, -1, mock.count("/notify"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308})
    void redirectsNeverForwardEitherCredentialOrBodyEvenToAnOtherwiseAllowedTarget(int status) throws Exception {
        try (var source = new HttpMock(); var destination = new RawHttpPeer(socket -> { RawHttpPeer.readRequest(socket); RawHttpPeer.write(socket, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"); })) {
            source.route("/business", e -> { e.getResponseHeaders().set("Location", destination.uri("http", "/stolen").toString()); HttpMock.respond(e, status, "ignored"); });
            source.route("/token", e -> { e.getResponseHeaders().set("Location", destination.uri("http", "/stolen").toString()); HttpMock.respond(e, status, "ignored"); });
            var policy = policy("test", Set.of("127.0.0.1"), Set.of(443), Set.of("127.0.0.1"),
                    Set.of(source.uri("/").getPort(), destination.uri("http", "/").getPort()), Set.of("127.0.0.1/32"));
            try (var client = new ControlledHttpClient(org.apache.hc.client5.http.SystemDefaultDnsResolver.INSTANCE, 2, policy::allowsConnectionAddress)) {
                assertEquals(status, new BusinessHttpSender(client, policy).send(snapshot(source.uri("/business")), authentication(), BUDGET).statusCode());
                var tokenFailure = assertThrows(TokenFailure.class, () -> new OAuthTokenClient(client, policy).exchange(token(source.uri("/token")), BUDGET));
                assertEquals("TOKEN_HTTP_REJECTED", tokenFailure.code()); assertFalse(tokenFailure.retryable());
                assertEquals(1, source.count("/business")); assertEquals(1, source.count("/token"));
                assertEquals("Bearer cp08-credential-sentinel", source.journal().get(0).firstHeader("Authorization"));
                assertTrue(source.journal().get(1).firstHeader("Authorization").startsWith("Basic "));
                assertEquals(0, destination.accepted.get(), "No TCP connection may forward a request or its credentials");
                evidence("redirect-" + status, "business-and-token", List.of(), destination.accepted.get(), 0);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inheritedProxiesCookiesAndAuthenticationChallengesDoNotCreateHiddenRequests(boolean tokenPath) throws Exception {
        var previous = new java.util.HashMap<String, String>();
        try (var source = new HttpMock(); var proxy = new RawHttpPeer(socket -> { })) {
            var calls = new AtomicInteger();
            source.route("/endpoint", e -> {
                int number = calls.incrementAndGet();
                e.getResponseHeaders().add("Set-Cookie", "cp08-private-cookie=value; Path=/");
                e.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"supplier\"");
                HttpMock.respond(e, number == 1 ? 401 : 200, tokenPath
                        ? number == 1 ? "{\"error\":\"invalid_client\"}" : "{\"access_token\":\"opaque-test-value\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                        : "ignored");
            });
            Map<String, String> settings = Map.of("http.proxyHost", "127.0.0.1", "https.proxyHost", "127.0.0.1",
                    "http.proxyPort", Integer.toString(proxy.uri("http", "/").getPort()), "https.proxyPort", Integer.toString(proxy.uri("http", "/").getPort()),
                    "http.nonProxyHosts", "", "https.nonProxyHosts", "");
            settings.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
            var target = source.uri("/endpoint");
            var policy = policy("test", Set.of("127.0.0.1"), Set.of(443), Set.of("127.0.0.1"), Set.of(target.getPort()), Set.of("127.0.0.1/32"));
            try (var client = new ControlledHttpClient(org.apache.hc.client5.http.SystemDefaultDnsResolver.INSTANCE, 1, policy::allowsConnectionAddress)) {
                if (tokenPath) {
                    var oauth = new OAuthTokenClient(client, policy);
                    assertEquals("TOKEN_INVALID_CLIENT", assertThrows(TokenFailure.class, () -> oauth.exchange(token(target), BUDGET)).code());
                    assertEquals(1, calls.get(), "A 401 challenge must not cause a second implicit exchange");
                    assertEquals("opaque-test-value", oauth.exchange(token(target), BUDGET).accessToken());
                } else {
                    var sender = new BusinessHttpSender(client, policy);
                    assertEquals(401, sender.send(snapshot(target), authentication(), BUDGET).statusCode());
                    assertEquals(1, calls.get(), "A 401 challenge must not cause a second implicit exchange");
                    assertEquals(200, sender.send(snapshot(target), authentication(), BUDGET).statusCode());
                }
                assertEquals(2, source.journal().size()); assertEquals(0, proxy.accepted.get());
                for (var request : source.journal()) { assertNull(request.firstHeader("Cookie")); assertNull(request.firstHeader("Proxy-Authorization")); }
                evidence("no-implicit-state-" + tokenPath, tokenPath ? "token" : "business", List.of(), proxy.accepted.get(), source.journal().size());
            }
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        }
    }

    private static void assertTargetRejected(String role, ControlledHttpClient client, OutboundPolicy policy, URI target) {
        if (role.equals("business")) assertEquals(BusinessHttpSender.FailureKind.TARGET_REJECTED,
                assertThrows(BusinessHttpSender.SendFailure.class, () -> new BusinessHttpSender(client, policy).send(snapshot(target), authentication(), BUDGET)).kind());
        else assertEquals("TOKEN_TARGET_REJECTED", assertThrows(TokenFailure.class,
                () -> new OAuthTokenClient(client, policy).exchange(token(target), BUDGET)).code());
    }
    private static RequestSnapshot snapshot(URI target) { return new RequestSnapshot(target.toString(), "POST", Map.of("X-Event-Id", "cp08-event"), "application/json", "{\"private\":\"cp08-body-sentinel\"}".getBytes(StandardCharsets.UTF_8)); }
    private static ApiKeyAuthStrategy.Prepared authentication() { return new ApiKeyAuthStrategy.Prepared(Map.of("Authorization", "Bearer cp08-credential-sentinel"), "fixture-v1"); }
    private static TokenRequest token(URI target) { return new TokenRequest(target.toString(), "cp08-client", "cp08-secret-sentinel", "client_secret_basic", List.of("notify"), null); }
    private static OutboundPolicy policy(String environment, Set<String> hosts, Set<Integer> ports, Set<String> devHosts, Set<Integer> devPorts, Set<String> cidrs) {
        return new OutboundPolicy(new OutboundPolicy.Properties(hosts, ports, devHosts, devPorts, cidrs), new MockEnvironment().withProperty("app.environment", environment));
    }
    private static DnsResolver dns(List<String> observations, String... answers) {
        return new DnsResolver() {
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                observations.add(host + "=" + String.join(",", answers));
                InetAddress[] result = new InetAddress[answers.length];
                for (int i = 0; i < answers.length; i++) result[i] = InetAddress.getByName(answers[i]);
                return result;
            }
            public String resolveCanonicalHostname(String host) { throw new AssertionError("Unexpected second DNS resolution"); }
        };
    }
    private static void evidence(String scenario, String role, List<String> dns, long connections, long calls) throws Exception {
        var document = JSON.createObjectNode().put("scenario", "cp08-" + scenario).put("observedAt", Instant.now().toString())
                .put("targetRole", role).put("observedCalls", calls);
        if (connections >= 0) document.put("observedConnections", connections);
        document.set("dnsObservations", JSON.valueToTree(dns));
        Files.createDirectories(Path.of("target/evidence"));
        Files.writeString(Path.of("target/evidence/cp08-" + scenario + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
    }
}
