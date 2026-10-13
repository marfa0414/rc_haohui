package com.rc.notification.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.auth.ApiKeyAuthStrategy;
import com.rc.notification.auth.OAuthTokenClient;
import com.rc.notification.auth.TokenFailure;
import com.rc.notification.auth.TokenRequest;
import com.rc.notification.security.OutboundPolicy;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.RawHttpPeer;
import com.rc.notification.support.TransportEvidence;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** SEC-06: real bytes, cancellation observed by the peer, and reuse of the same bounded transport. */
@Timeout(25)
class OutboundResourceLimitsTest {
    private static final String TOKEN = "{\"access_token\":\"cp08-fake-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    private static final Duration BUDGET = Duration.ofSeconds(2);
    static Stream<Arguments> discardedBodies() {
        return Stream.of(200, 503).flatMap(status -> Stream.of("huge", "endless", "slow").map(mode -> Arguments.of(status, mode)));
    }
    @ParameterizedTest @MethodSource("discardedBodies")
    void businessStatusDoesNotWaitForOrDrainAnUnboundedResponseBody(int status, String mode) throws Exception {
        var arrived = new CountDownLatch(1); var physicallyClosed = new CountDownLatch(1); var written = new AtomicLong();
        try (var peer = new RawHttpPeer(socket -> {
            RawHttpPeer.readRequest(socket);
            RawHttpPeer.write(socket, "HTTP/1.1 " + status + " Result\r\n" + (mode.equals("endless")
                    ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 2147483647\r\n") + "\r\n");
            arrived.countDown();
            if (mode.equals("endless")) {
                byte[] chunk = ("400\r\n" + "x".repeat(1024) + "\r\n").getBytes(StandardCharsets.US_ASCII);
                try {
                    // A continuous stream that would never terminate absent cancellation. The cap protects the test host.
                    while (written.get() < 16L * 1024 * 1024) { socket.getOutputStream().write(chunk); socket.getOutputStream().flush(); written.addAndGet(chunk.length); }
                    throw new AssertionError("Client kept draining an endless business response");
                } catch (IOException clientCancelled) { physicallyClosed.countDown(); }
            } else {
                byte[] initial = new byte[mode.equals("huge") ? 8192 : 1];
                try { socket.getOutputStream().write(initial); socket.getOutputStream().flush(); written.addAndGet(initial.length); }
                catch (IOException clientCancelled) { physicallyClosed.countDown(); return; }
                if (RawHttpPeer.observePeerClose(socket, Duration.ofSeconds(2))) physicallyClosed.countDown();
            }
        }); var healthy = new HttpMock(); var f = new Transport(List.of(peer.uri("http", "/"), healthy.uri("/")), 1, SystemDefaultDnsResolver.INSTANCE)) {
            healthy.route("/healthy", e -> HttpMock.respond(e, 200, "ok"));
            long start = System.nanoTime();
            var result = f.sender.send(snapshot(peer.uri("http", "/business")), prepared(), BUDGET);
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(arrived.await(1, TimeUnit.SECONDS)); assertEquals(status, result.statusCode()); assertEquals(status == 200, result.acknowledged());
            assertTrue(elapsed < 1500, "Business acknowledgement must finish before the Body or the total deadline");
            assertTrue(physicallyClosed.await(2, TimeUnit.SECONDS), "Peer must observe physical close, not merely a returned Future");
            assertHealthy(f, healthy.uri("/healthy")); await(() -> peer.activeConnections() == 0);
            assertEquals(1, peer.accepted.get()); assertTrue(peer.unexpectedFailures().isEmpty(), peer.unexpectedFailures().toString());
            TransportEvidence.write("cp08-business-body-" + status + "-" + mode, Map.of("status", status, "bodyMode", mode,
                    "declaredBodyBytes", mode.equals("endless") ? -1L : 2147483647L, "serverWrittenBytesBeforeClose", written.get(),
                    "elapsedMillis", elapsed, "budgetMillis", 2000, "physicalConnectionClosed", true, "sameClientCapacityReusable", true));
        }
    }

    static Stream<Arguments> responseHeaders() {
        return Stream.of(false, true).flatMap(token -> Stream.of("total-exact", "total-excess", "line-excess", "count-exact", "count-excess")
                .map(shape -> Arguments.of(token, shape)));
    }
    @ParameterizedTest @MethodSource("responseHeaders")
    void responseHeadersHaveIndependentAggregateLineAndCountBounds(boolean token, String shape) throws Exception {
        byte[] body = TOKEN.getBytes(StandardCharsets.US_ASCII);
        String fields = headerFields(shape, body.length);
        var closed = new CountDownLatch(1);
        try (var peer = new RawHttpPeer(socket -> {
            RawHttpPeer.readRequest(socket);
            try { RawHttpPeer.write(socket, "HTTP/1.1 200 OK\r\n" + fields + "\r\n" + TOKEN); }
            catch (IOException rejected) { closed.countDown(); return; }
            if (RawHttpPeer.observePeerClose(socket, Duration.ofSeconds(2))) closed.countDown();
        }); var healthy = new HttpMock(); var f = new Transport(List.of(peer.uri("http", "/"), healthy.uri("/")), 1, SystemDefaultDnsResolver.INSTANCE)) {
            healthy.route("/healthy", e -> HttpMock.respond(e, 200, "ok"));
            boolean valid = shape.endsWith("exact"); long start = System.nanoTime();
            if (valid) f.call(token, peer.uri("http", "/headers"), BUDGET);
            else assertNotNull(f.failure(token, peer.uri("http", "/headers"), BUDGET));
            assertTrue(closed.await(2, TimeUnit.SECONDS)); assertHealthy(f, healthy.uri("/healthy"));
            assertEquals(1, peer.accepted.get()); assertTrue(peer.unexpectedFailures().isEmpty());
            TransportEvidence.write("cp08-response-headers-" + shape + "-" + path(token), Map.of("path", path(token), "shape", shape,
                    "headerFieldBytes", fields.length(), "fieldCount", fields.split("\r\n").length, "accepted", valid,
                    "elapsedMillis", Duration.ofNanos(System.nanoTime() - start).toMillis(), "budgetMillis", 2000, "physicalConnectionClosed", true, "sameClientCapacityReusable", true));
        }
    }

    static Stream<Arguments> slowStages() { return Stream.of(false, true).flatMap(token -> Stream.of("tls", "headers").map(stage -> Arguments.of(token, stage))); }
    @ParameterizedTest @MethodSource("slowStages")
    void outerDeadlineCancelsStalledTlsAndPartialHeadersInBothPaths(boolean token, String stage) throws Exception {
        var reached = new CountDownLatch(1); var closed = new CountDownLatch(1);
        try (var peer = new RawHttpPeer(socket -> {
            if (stage.equals("headers")) { RawHttpPeer.readRequest(socket); RawHttpPeer.write(socket, "HTTP/1.1 200 OK\r\nX-Slow: "); }
            reached.countDown();
            if (RawHttpPeer.observePeerClose(socket, Duration.ofSeconds(2))) closed.countDown();
        }); var healthy = new HttpMock(); var f = new Transport(List.of(peer.uri(stage.equals("tls") ? "https" : "http", "/"), healthy.uri("/")), 1, SystemDefaultDnsResolver.INSTANCE)) {
            healthy.route("/healthy", e -> HttpMock.respond(e, 200, "ok"));
            long start = System.nanoTime();
            assertEquals(token ? "TOKEN_TIMEOUT" : "TIMEOUT", f.failure(token, peer.uri(stage.equals("tls") ? "https" : "http", "/slow"), Duration.ofMillis(300)));
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(reached.await(1, TimeUnit.SECONDS)); assertTrue(elapsed < 1500); assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertHealthy(f, healthy.uri("/healthy")); assertEquals(1, peer.accepted.get());
            TransportEvidence.write("cp08-slow-" + stage + "-" + path(token), Map.of("path", path(token), "stage", stage,
                    "budgetMillis", 300, "elapsedMillis", elapsed, "connections", peer.accepted.get(), "physicalConnectionClosed", true, "sameClientCapacityReusable", true));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uninterruptibleDnsRetainsOnlyItsBoundedSlotsAndNeverConnectsAfterDeadline(boolean token) throws Exception {
        long scenarioStarted = System.nanoTime();
        var started = new CountDownLatch(2); var release = new CountDownLatch(1); var returned = new CountDownLatch(2);
        var resolutions = new AtomicInteger(); var active = new AtomicInteger(); var peak = new AtomicInteger();
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (host.equals("slow.supplier.test")) {
                    resolutions.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), Math::max); started.countDown();
                    try { while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { /* Simulated OS resolver. */ } } }
                    finally { active.decrementAndGet(); returned.countDown(); }
                }
                return new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        var callers = Executors.newFixedThreadPool(2);
        try (var server = new HttpMock(); var f = new Transport(List.of(server.uri("/")), 2, resolver)) {
            server.route("/late", e -> HttpMock.respond(e, 200, TOKEN)); server.route("/healthy", e -> HttpMock.respond(e, 200, "ok"));
            URI late = server.virtualUri("slow.supplier.test", "/late");
            var first = callers.submit(() -> f.failure(token, late, Duration.ofMillis(350)));
            var second = callers.submit(() -> f.failure(token, late, Duration.ofMillis(350)));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            int rejected = 0;
            for (int n = 0; n < 20; n++) { assertEquals(token ? "TOKEN_CAPACITY" : "CAPACITY", f.failure(token, late, BUDGET)); rejected++; }
            assertEquals(token ? "TOKEN_TIMEOUT" : "TIMEOUT", first.get(2, TimeUnit.SECONDS));
            assertEquals(token ? "TOKEN_TIMEOUT" : "TIMEOUT", second.get(2, TimeUnit.SECONDS));
            assertEquals(2, resolutions.get()); assertEquals(2, peak.get()); assertEquals(2, active.get());
            assertEquals(token ? "TOKEN_CAPACITY" : "CAPACITY", f.failure(token, late, BUDGET));
            release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS));
            assertAllCapacityReusable(f, server, 2); assertEquals(0, server.count("/late")); assertEquals(0, active.get());
            TransportEvidence.write("cp08-dns-bound-" + path(token), Map.of("path", path(token), "configuredCapacity", 2, "peakDnsCalls", peak.get(),
                    "dnsResolutions", resolutions.get(), "excessRejected", rejected + 1, "postDeadlineBusinessRequests", server.count("/late"), "sameClientCapacityReusable", true,
                    "callBudgetMillis", 350, "scenarioElapsedMillis", Duration.ofNanos(System.nanoTime() - scenarioStarted).toMillis()));
        } finally { release.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void repeatedConcurrentInterruptionsClosePhysicalSocketsAndReturnEverySlot(boolean token) throws Exception {
        long scenarioStarted = System.nanoTime();
        var arrived = new AtomicReference<>(new CountDownLatch(4)); var closed = new CountDownLatch(12);
        try (var peer = new RawHttpPeer(socket -> {
            RawHttpPeer.readRequest(socket); arrived.get().countDown();
            if (RawHttpPeer.observePeerClose(socket, Duration.ofSeconds(3))) closed.countDown();
        }); var healthy = new HttpMock(); var f = new Transport(List.of(peer.uri("http", "/"), healthy.uri("/")), 4, SystemDefaultDnsResolver.INSTANCE)) {
            healthy.route("/healthy", e -> HttpMock.respond(e, 200, "ok"));
            int cancelled = 0;
            for (int round = 0; round < 3; round++) {
                arrived.set(new CountDownLatch(4)); var callers = new ArrayList<Thread>(); var outcomes = new java.util.concurrent.CopyOnWriteArrayList<String>();
                var preserved = new AtomicInteger(); var done = new CountDownLatch(4);
                try {
                    for (int n = 0; n < 4; n++) {
                        var caller = new Thread(() -> {
                            try { outcomes.add(f.failure(token, peer.uri("http", "/held"), Duration.ofSeconds(5))); if (Thread.currentThread().isInterrupted()) preserved.incrementAndGet(); }
                            finally { done.countDown(); }
                        }, "cp08-cancel-caller"); callers.add(caller); caller.start();
                    }
                    assertTrue(arrived.get().await(2, TimeUnit.SECONDS)); assertEquals(4, peer.activeConnections());
                    assertEquals(token ? "TOKEN_CAPACITY" : "CAPACITY", f.failure(token, peer.uri("http", "/held"), BUDGET));
                    callers.forEach(Thread::interrupt); assertTrue(done.await(2, TimeUnit.SECONDS));
                    assertEquals(List.of(token ? "TOKEN_CANCELLED" : "CANCELLED", token ? "TOKEN_CANCELLED" : "CANCELLED",
                            token ? "TOKEN_CANCELLED" : "CANCELLED", token ? "TOKEN_CANCELLED" : "CANCELLED"), outcomes);
                    assertEquals(4, preserved.get()); cancelled += 4;
                    await(() -> peer.activeConnections() == 0);
                    assertAllCapacityReusable(f, healthy, 4);
                } finally { for (var caller : callers) { caller.interrupt(); caller.join(2000); assertFalse(caller.isAlive()); } }
            }
            assertTrue(closed.await(1, TimeUnit.SECONDS)); assertEquals(12, peer.accepted.get()); assertEquals(12, healthy.journal().size());
            assertTrue(peer.unexpectedFailures().isEmpty());
            TransportEvidence.write("cp08-concurrent-cancellation-" + path(token), Map.of("path", path(token), "configuredCapacity", 4,
                    "rounds", 3, "cancelledCalls", cancelled, "physicalConnectionsClosed", 12, "postCancellationHealthyRequests", 12, "remainingActiveConnections", peer.activeConnections(),
                    "callBudgetMillis", 5000, "scenarioElapsedMillis", Duration.ofNanos(System.nanoTime() - scenarioStarted).toMillis()));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void compressedResponsesAreNeverTransparentlyExpanded(boolean token) throws Exception {
        long start = System.nanoTime();
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(("{\"padding\":\"" + "x".repeat(131072) + "\"}").getBytes(StandardCharsets.US_ASCII)); }
        byte[] wire = compressed.toByteArray();
        try (var peer = new RawHttpPeer(socket -> {
            RawHttpPeer.readRequest(socket); RawHttpPeer.write(socket, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + wire.length + "\r\n\r\n");
            socket.getOutputStream().write(wire); socket.getOutputStream().flush();
        }); var f = new Transport(List.of(peer.uri("http", "/")), 1, SystemDefaultDnsResolver.INSTANCE)) {
            if (token) assertEquals("TOKEN_PROTOCOL_ERROR", f.failure(true, peer.uri("http", "/compressed"), BUDGET));
            else assertEquals(200, f.sender.send(snapshot(peer.uri("http", "/compressed")), prepared(), BUDGET).statusCode());
            assertEquals(1, peer.accepted.get());
            TransportEvidence.write("cp08-compression-disabled-" + path(token), Map.of("path", path(token), "wireBytes", wire.length,
                    "expandedBytesAtPeer", 131072, "automaticResponseDecompression", false, "requests", peer.accepted.get(),
                    "callBudgetMillis", 2000, "scenarioElapsedMillis", Duration.ofNanos(System.nanoTime() - start).toMillis()));
        }
    }

    private static String headerFields(String shape, int length) {
        String base = "Content-Length: " + length + "\r\n";
        if (shape.startsWith("total-")) {
            int remaining = (shape.equals("total-exact") ? 65536 : 65537) - base.length(); var fields = new StringBuilder(base);
            for (int n = 0; n < 8; n++) { int line = remaining / (8 - n); fields.append("X-").append(n).append(": ").append("v".repeat(line - 7)).append("\r\n"); remaining -= line; }
            assertEquals(shape.equals("total-exact") ? 65536 : 65537, fields.length()); return fields.toString();
        }
        if (shape.equals("line-excess")) return base + "X-Large: " + "v".repeat(20000) + "\r\n";
        int count = shape.equals("count-exact") ? 100 : 101; var fields = new StringBuilder(base);
        for (int n = 1; n < count; n++) fields.append("X-").append(n).append(": v\r\n"); return fields.toString();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Transport resource was not released within two seconds");
    }
    private static void assertHealthy(Transport f, URI uri) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (true) {
            try { assertEquals(200, f.sender.send(snapshot(uri), prepared(), BUDGET).statusCode()); return; }
            catch (BusinessHttpSender.SendFailure failure) { if (failure.kind() != BusinessHttpSender.FailureKind.CAPACITY || System.nanoTime() >= end) throw failure; Thread.sleep(5); }
        }
    }
    private static void assertAllCapacityReusable(Transport f, HttpMock healthy, int capacity) throws Exception {
        var allArrived = new CountDownLatch(capacity); var release = new CountDownLatch(1);
        String path = "/capacity-" + java.util.UUID.randomUUID();
        healthy.route(path, exchange -> {
            allArrived.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Capacity probe barrier timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Capacity probe cancelled"); }
            HttpMock.respond(exchange, 200, "ok");
        });
        var callers = Executors.newFixedThreadPool(capacity);
        try {
            var results = new ArrayList<java.util.concurrent.Future<?>>();
            for (int n = 0; n < capacity; n++) results.add(callers.submit(() -> { assertHealthy(f, healthy.uri(path)); return null; }));
            assertTrue(allArrived.await(2, TimeUnit.SECONDS), "All configured transport slots must be usable concurrently after cancellation");
            release.countDown();
            for (var result : results) result.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    private static String path(boolean token) { return token ? "token" : "business"; }
    private static RequestSnapshot snapshot(URI uri) { return new RequestSnapshot(uri.toString(), "POST", Map.of(), "application/json", new byte[0]); }
    private static ApiKeyAuthStrategy.Prepared prepared() { return new ApiKeyAuthStrategy.Prepared(Map.of(), "fixture-v1"); }
    private static final class Transport implements AutoCloseable {
        final ControlledHttpClient client; final BusinessHttpSender sender; final OAuthTokenClient tokens;
        Transport(List<URI> targets, int capacity, DnsResolver resolver) {
            Set<Integer> ports = targets.stream().map(URI::getPort).collect(java.util.stream.Collectors.toSet());
            var policy = new OutboundPolicy(new OutboundPolicy.Properties(Set.of("127.0.0.1", "slow.supplier.test"), ports,
                    Set.of("127.0.0.1", "slow.supplier.test"), ports, Set.of("127.0.0.1/32")), new MockEnvironment().withProperty("app.environment", "test"));
            client = new ControlledHttpClient(resolver, capacity, policy::allowsConnectionAddress);
            sender = new BusinessHttpSender(client, policy); tokens = new OAuthTokenClient(client, policy);
        }
        void call(boolean token, URI uri, Duration budget) throws Exception {
            if (token) assertEquals("cp08-fake-token", tokens.exchange(new TokenRequest(uri.toString(), "test-client", "test-secret", "client_secret_post", List.of(), null), budget).accessToken());
            else assertEquals(200, sender.send(snapshot(uri), prepared(), budget).statusCode());
        }
        String failure(boolean token, URI uri, Duration budget) {
            if (token) return assertThrows(TokenFailure.class, () -> tokens.exchange(new TokenRequest(uri.toString(), "test-client", "test-secret", "client_secret_post", List.of(), null), budget)).code();
            return assertThrows(BusinessHttpSender.SendFailure.class, () -> sender.send(snapshot(uri), prepared(), budget)).kind().name();
        }
        @Override public void close() throws IOException { client.close(); }
    }
}
