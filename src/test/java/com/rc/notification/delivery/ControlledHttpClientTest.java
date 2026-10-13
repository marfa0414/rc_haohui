package com.rc.notification.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CP01 transport feasibility only: these tests do not claim the full CP08 SSRF policy exists. */
@Timeout(15)
class ControlledHttpClientTest {
    private static final Duration DEADLINE = Duration.ofSeconds(3);

    @Test
    void sendsOneRealHttpRequestWithExactBytesAndInjectedDns() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(resolutions), 2)) {
            mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "ignored"));
            byte[] body = "{\"name\":\"中文\\nquoted\\\"\",\"quantity\":1234567890123456789}"
                    .getBytes(StandardCharsets.UTF_8);

            var response = client.execute(mock.virtualUri("supplier.invalid", "/notify"), "POST",
                    Map.of("Content-Type", "application/json", "X-Test-Header", "cp01"), body, DEADLINE);

            assertEquals(200, response.statusCode());
            assertEquals(1, resolutions.get(), "Connection must use the injected resolver, without a second lookup");
            assertEquals(1, mock.journal().size());
            var received = mock.journal().get(0);
            assertEquals("POST", received.method());
            assertArrayEquals(body, received.body());
            assertEquals("cp01", received.firstHeader("X-Test-Header"));
            assertTrue(received.firstHeader("Host").startsWith("supplier.invalid:"),
                    "Pinning the destination must retain the original host authority");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308})
    void neverFollowsRedirects(int status) throws Exception {
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", mock.uri("/forbidden").toString());
                HttpMock.respond(exchange, status, "redirect");
            });
            mock.route("/forbidden", exchange -> HttpMock.respond(exchange, 200, "must not be reached"));
            var response = client.execute(mock.uri("/redirect"), "GET",
                    Map.of("Authorization", "Bearer test-only-token"), new byte[0], DEADLINE);

            assertEquals(status, response.statusCode());
            assertEquals(1, mock.count("/redirect"));
            assertEquals(0, mock.count("/forbidden"), "No redirect request, including credential forwarding");
        }
    }

    @Test
    void neverRetriesAnIdempotentRequestAfterConnectionReset() throws Exception {
        try (ResetServer server = new ResetServer();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            assertThrows(IOException.class,
                    () -> client.execute(server.uri(), "GET", Map.of(), new byte[0], DEADLINE));
            assertEquals(1, server.accepted.get(), "A single execute call must not retry even a GET");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void retryAfterDoesNotCauseATransparentRetry(int status) throws Exception {
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/retry-after", exchange -> {
                exchange.getResponseHeaders().set("Retry-After", "0");
                HttpMock.respond(exchange, status, "retry belongs to the scheduler");
            });
            var response = client.execute(mock.uri("/retry-after"), "GET", Map.of(), new byte[0], DEADLINE);
            assertEquals(status, response.statusCode());
            assertEquals(java.util.List.of("0"), response.headers().get("retry-after"));
            assertEquals(1, mock.count("/retry-after"), "Retry-After must never create an unrecorded physical send");
        }
    }

    @Test
    void finalResponseHeadersAreEnoughWithoutWaitingForTheBody() throws Exception {
        CountDownLatch headersWritten = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/endless-body", exchange -> {
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headersWritten.countDown();
                try {
                    if (!releaseBody.await(5, TimeUnit.SECONDS)) throw new IOException("body barrier timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            mock.route("/healthy", exchange -> HttpMock.respond(exchange, 200, "ok"));
            long started = System.nanoTime();
            var response = client.execute(mock.uri("/endless-body"), "POST", Map.of(), new byte[0], Duration.ofSeconds(2));
            assertTrue(headersWritten.await(1, TimeUnit.SECONDS));
            assertEquals(202, response.statusCode(), "Closing an unused response body must retain the confirmed status");
            assertEquals(1, releaseBody.getCount(), "The response body is still blocked when execution returns");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(200, executeWhenCapacityReleased(client, mock.uri("/healthy")).statusCode());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void returnsAuthenticationChallengeWithoutResending() throws Exception {
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/challenge", exchange -> {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"supplier\"");
                HttpMock.respond(exchange, 401, "unauthorized");
            });
            var response = client.execute(mock.uri("/challenge"), "POST",
                    Map.of("Authorization", "Bearer test-only-token"), new byte[0], DEADLINE);
            assertEquals(401, response.statusCode());
            assertEquals(1, mock.count("/challenge"));
        }
    }

    @Test
    void doesNotRetainCookiesAcrossRequests() throws Exception {
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/cookie", exchange -> {
                exchange.getResponseHeaders().set("Set-Cookie", "supplier_session=test-only; Path=/");
                HttpMock.respond(exchange, 200, "ok");
            });
            client.execute(mock.uri("/cookie"), "GET", Map.of(), new byte[0], DEADLINE);
            client.execute(mock.uri("/cookie"), "GET", Map.of(), new byte[0], DEADLINE);
            assertEquals(2, mock.count("/cookie"));
            assertNull(mock.journal().get(1).firstHeader("Cookie"));
        }
    }

    @Test
    void aDnsRejectionProducesNoHttpConnection() throws Exception {
        AtomicInteger rejectedLookups = new AtomicInteger();
        DnsResolver resolver = new TestResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                rejectedLookups.incrementAndGet();
                throw new UnknownHostException("test policy rejection");
            }
        };
        try (HttpMock mock = new HttpMock(); ControlledHttpClient client = new ControlledHttpClient(resolver, 1)) {
            mock.route("/blocked", exchange -> HttpMock.respond(exchange, 200, "should not arrive"));
            assertThrows(IOException.class, () -> client.execute(mock.virtualUri("blocked.invalid", "/blocked"),
                    "POST", Map.of(), new byte[0], DEADLINE));
            assertEquals(1, rejectedLookups.get());
            assertEquals(0, mock.journal().size());
        }
    }

    @Test
    void validatedResolverRejectsMixedAddressesBeforeAnyRealConnection() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger checkedAddresses = new AtomicInteger();
        DnsResolver delegate = new TestResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                resolutions.incrementAndGet();
                return new InetAddress[] {
                    InetAddress.getByName("127.0.0.1"),
                    InetAddress.getByName("127.0.0.2")
                };
            }
        };
        var validated = new ValidatedDnsResolver(delegate, address -> {
            checkedAddresses.incrementAndGet();
            return address.getHostAddress().equals("127.0.0.1");
        });
        try (ResetServer probe = new ResetServer(); ControlledHttpClient client = new ControlledHttpClient(validated, 1)) {
            URI target = URI.create("http://mixed-addresses.invalid:" + probe.listener.getLocalPort() + "/blocked");
            assertThrows(IOException.class, () -> client.execute(target, "GET", Map.of(), new byte[0], DEADLINE));
            assertEquals(1, resolutions.get());
            assertEquals(2, checkedAddresses.get(), "Validate the full DNS answer before connecting to even its allowed first address");
            assertEquals(0, probe.accepted.get(), "The production resolver must reject before any TCP connection reaches the allowed address");
        }
    }

    @Test
    void totalTimeoutCancelsSlowResponseAndReleasesCapacity() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/slow", exchange -> {
                requestArrived.countDown();
                try {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) throw new IOException("test response barrier timed out");
                    HttpMock.respond(exchange, 200, "late");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            mock.route("/healthy", exchange -> HttpMock.respond(exchange, 200, "ok"));
            long started = System.nanoTime();
            assertThrows(TimeoutException.class, () -> client.execute(mock.uri("/slow"), "POST",
                    Map.of(), new byte[0], Duration.ofMillis(500)));
            assertTrue(requestArrived.await(1, TimeUnit.SECONDS), "The test must reach a real server before timing out");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(200, executeWhenCapacityReleased(client, mock.uri("/healthy")).statusCode(),
                    "Cancelled I/O must release both capacity and its pooled connection within a bounded interval");
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void callerInterruptionCancelsTheRequestAndReleasesCapacity() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> callerOutcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptRetained = new AtomicReference<>(false);
        Thread caller = null;
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/interrupted", exchange -> {
                requestArrived.countDown();
                try {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) throw new IOException("response barrier timed out");
                    HttpMock.respond(exchange, 200, "late");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            mock.route("/healthy", exchange -> HttpMock.respond(exchange, 200, "ok"));
            caller = new Thread(() -> {
                try {
                    client.execute(mock.uri("/interrupted"), "POST", Map.of(), new byte[0], DEADLINE);
                } catch (Throwable outcome) {
                    callerOutcome.set(outcome);
                    interruptRetained.set(Thread.currentThread().isInterrupted());
                } finally {
                    callerFinished.countDown();
                }
            }, "cp01-interrupted-caller");
            caller.start();
            assertTrue(requestArrived.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerFinished.await(2, TimeUnit.SECONDS), "Caller interruption must not wait for a response");
            assertInstanceOf(InterruptedException.class, callerOutcome.get());
            assertTrue(interruptRetained.get(), "The cancellation boundary must preserve the interrupt flag");
            assertEquals(200, executeWhenCapacityReleased(client, mock.uri("/healthy")).statusCode());
            assertEquals(1, mock.count("/interrupted"));
        } finally {
            releaseResponse.countDown();
            if (caller != null) {
                caller.interrupt();
                caller.join(2000);
            }
        }
    }

    @Test
    void dnsThatIgnoresInterruptionCannotConnectAfterDeadline() throws Exception {
        CountDownLatch dnsStarted = new CountDownLatch(1);
        CountDownLatch releaseDns = new CountDownLatch(1);
        CountDownLatch dnsReturned = new CountDownLatch(1);
        CountDownLatch forbiddenRequest = new CountDownLatch(1);
        DnsResolver resolver = new TestResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                dnsStarted.countDown();
                boolean interrupted = false;
                while (releaseDns.getCount() != 0) {
                    try {
                        releaseDns.await();
                    } catch (InterruptedException ignored) {
                        interrupted = true; // Simulate an uninterruptible resolver, without leaving it blocked forever.
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                dnsReturned.countDown();
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }
        };
        try (HttpMock mock = new HttpMock(); ControlledHttpClient client = new ControlledHttpClient(resolver, 1)) {
            mock.route("/late", exchange -> {
                forbiddenRequest.countDown();
                HttpMock.respond(exchange, 200, "must not arrive");
            });
            long started = System.nanoTime();
            assertThrows(TimeoutException.class, () -> client.execute(mock.virtualUri("slow-dns.invalid", "/late"),
                    "GET", Map.of(), new byte[0], Duration.ofMillis(500)));
            assertTrue(dnsStarted.await(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertThrows(RejectedExecutionException.class, () -> client.execute(mock.uri("/healthy"),
                    "GET", Map.of(), new byte[0], DEADLINE),
                    "An uninterruptible DNS operation must retain its bounded execution slot until it exits");
            mock.route("/healthy", exchange -> HttpMock.respond(exchange, 200, "ok"));
            releaseDns.countDown();
            assertTrue(dnsReturned.await(1, TimeUnit.SECONDS));
            assertFalse(forbiddenRequest.await(400, TimeUnit.MILLISECONDS),
                    "Expired resolution must never be followed by a connection");
            assertEquals(0, mock.journal().size());
            assertEquals(200, executeWhenCapacityReleased(client, mock.uri("/healthy")).statusCode());
        } finally {
            releaseDns.countDown();
        }
    }

    @Test
    void rejectsExcessConcurrentRequestsInsteadOfBuildingAnUnboundedQueue() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (HttpMock mock = new HttpMock();
                ControlledHttpClient client = new ControlledHttpClient(loopbackResolver(new AtomicInteger()), 1)) {
            mock.route("/held", exchange -> {
                arrived.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test barrier timeout");
                    HttpMock.respond(exchange, 200, "ok");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            var first = caller.submit(() -> client.execute(mock.uri("/held"), "POST", Map.of(), new byte[0], DEADLINE));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class,
                    () -> client.execute(mock.uri("/held"), "POST", Map.of(), new byte[0], DEADLINE));
            assertEquals(1, mock.count("/held"));
            release.countDown();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    private static DnsResolver loopbackResolver(AtomicInteger calls) {
        return new TestResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                calls.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            }
        };
    }

    private static ControlledHttpClient.Response executeWhenCapacityReleased(ControlledHttpClient client, URI uri)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (true) {
            try {
                return client.execute(uri, "POST", Map.of(), new byte[0], DEADLINE);
            } catch (RejectedExecutionException stillCancelling) {
                if (System.nanoTime() >= deadline) {
                    fail("Cancelled transport did not release its only execution slot within two seconds", stillCancelling);
                }
                // Poll a bounded cancellation transition; rejected calls never entered the HTTP transport.
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        }
    }

    private abstract static class TestResolver implements DnsResolver {
        @Override public String resolveCanonicalHostname(String host) { return host; }
    }

    private static final class ResetServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicInteger accepted = new AtomicInteger();

        private ResetServer() throws IOException {
            executor.submit(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        accepted.incrementAndGet();
                        socket.setSoTimeout(2000);
                        socket.getInputStream().read();
                        socket.setSoLinger(true, 0);
                    } catch (SocketException closed) {
                        if (!listener.isClosed()) throw new IllegalStateException(closed);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            });
        }

        private URI uri() { return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/reset"); }

        @Override public void close() throws IOException {
            listener.close();
            executor.shutdownNow();
        }
    }
}
