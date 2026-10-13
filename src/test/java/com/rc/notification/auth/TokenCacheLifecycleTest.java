package com.rc.notification.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.delivery.ControlledHttpClient;
import com.rc.notification.security.OutboundPolicy;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** TC-AUTH-11: real HTTP cancellation, waiter ownership, and bounded failure cooldown. */
@Timeout(20)
class TokenCacheLifecycleTest {
    @Test
    void interruptingOneWaiterLeavesTheSharedExchangeAndOtherWaiterAlive() throws Exception {
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var f = new LifecycleFixture()) {
            f.mock.route("/token", exchange -> {
                arrived.countDown();
                try {
                    if (release.await(5, TimeUnit.SECONDS)) {
                        HttpMock.respond(exchange, 200, OAuthFixture.response("survives-waiter-interrupt", 3600));
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            var callers = Executors.newSingleThreadExecutor();
            var interruptedOutcome = new CompletableFuture<Throwable>();
            var waiter = new Thread(() -> {
                try { f.get(); interruptedOutcome.complete(new AssertionError("Interrupted waiter unexpectedly received a token")); }
                catch (Throwable failure) { interruptedOutcome.complete(failure); }
            }, "token-interrupt-test-waiter");
            try {
                var survivingWaiter = callers.submit(f::get);
                assertTrue(arrived.await(2, TimeUnit.SECONDS));
                waiter.start();
                f.awaitWaiters(1);
                waiter.interrupt();
                assertInstanceOf(InterruptedException.class, interruptedOutcome.get(2, TimeUnit.SECONDS));
                waiter.join(2000);
                assertFalse(waiter.isAlive());
                assertFalse(survivingWaiter.isDone(), "A waiter does not own cancellation of the shared exchange");
                assertEquals(1, f.cache.activeLeaderCount());
                assertEquals(1, f.cache.inFlightCount());
                assertEquals(1, f.mock.count("/token"));

                release.countDown();
                var token = survivingWaiter.get(4, TimeUnit.SECONDS);
                assertEquals("survives-waiter-interrupt", token.accessToken());
                assertEquals(token.generation(), f.get().generation());
                assertEquals(1, f.mock.count("/token"));
            } finally {
                release.countDown();
                waiter.interrupt();
                waiter.join(2000);
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
            }
        } finally { release.countDown(); }
    }

    @Test
    void closeReleasesEveryWaiterAndCancelsTheLiveTransportBeforeServerCompletion() throws Exception {
        var arrived = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        try (var f = new LifecycleFixture()) {
            f.mock.route("/token", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                arrived.countDown();
                try { releaseServer.await(8, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            f.mock.route("/probe", exchange -> HttpMock.respond(exchange, 200, OAuthFixture.response("released-transport-slot", 3600)));
            var callers = Executors.newFixedThreadPool(4);
            try {
                var results = new ArrayList<java.util.concurrent.Future<TokenFailure>>();
                results.add(callers.submit(() -> assertThrows(TokenFailure.class, f::get)));
                assertTrue(arrived.await(2, TimeUnit.SECONDS));
                for (int i = 0; i < 3; i++) results.add(callers.submit(() -> assertThrows(TokenFailure.class, f::get)));
                f.awaitWaiters(3);
                assertEquals(1, f.cache.activeLeaderCount());

                f.cache.close();
                for (var result : results) {
                    var failure = result.get(2, TimeUnit.SECONDS);
                    assertEquals("TOKEN_CANCELLED", failure.code());
                    assertTrue(failure.retryable());
                }
                assertEquals(0, f.cache.activeLeaderCount());
                assertEquals(0, f.cache.inFlightCount());
                assertEquals(0, f.cache.size());
                assertEquals("TOKEN_CANCELLED", assertThrows(TokenFailure.class, f::get).code());

                // The transport has exactly one permit. Reusing it while the original server
                // remains blocked proves close cancelled physical I/O, not just the shared future.
                var probe = f.request("/probe");
                TokenResult reused = null;
                long bound = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < bound && reused == null) {
                    try { reused = f.client.exchange(probe, Duration.ofSeconds(1)); }
                    catch (TokenFailure failure) {
                        assertEquals("TOKEN_CAPACITY", failure.code(), "Only asynchronous permit release may require observation again");
                        LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                    }
                }
                assertNotNull(reused, "Cancelled I/O must release its sole transport permit");
                assertEquals("released-transport-slot", reused.accessToken());
                assertEquals(1, releaseServer.getCount(), "The original endpoint has not been allowed to complete");
                assertEquals(1, f.mock.count("/token"));
                assertEquals(1, f.mock.count("/probe"));
            } finally {
                releaseServer.countDown();
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
            }
        } finally { releaseServer.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tokenRateLimitHonorsSecondsAndHttpDateBeyondTheShortFailureCooldown(boolean httpDate) throws Exception {
        try (var f = new LifecycleFixture()) {
            String retryAfter = httpDate
                    ? HttpMock.httpDate(f.wall.get().plusSeconds(120)) : "120";
            var status = new AtomicInteger(429);
            f.mock.route("/token", exchange -> {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
                HttpMock.respond(exchange, status.get(), OAuthFixture.response("after-rate-limit", 3600));
            });
            var failure = assertThrows(TokenFailure.class, f::get);
            assertEquals("TOKEN_RATE_LIMITED", failure.code());
            assertTrue(failure.retryable());
            assertEquals(List.of(retryAfter), f.cache.cooldownRetryAfter(f.key));
            assertEquals(Duration.ofSeconds(120), f.cache.cooldownRemaining(f.key));

            f.advance(Duration.ofSeconds(119));
            status.set(200);
            assertEquals(Duration.ofSeconds(1), f.cache.cooldownRemaining(f.key));
            assertEquals("TOKEN_RATE_LIMITED", assertThrows(TokenFailure.class, f::get).code());
            assertEquals(1, f.mock.count("/token"), "The default short cooldown cannot shorten Retry-After");
            f.advance(Duration.ofSeconds(1));
            assertEquals(Duration.ZERO, f.cache.cooldownRemaining(f.key));
            assertEquals("after-rate-limit", f.get().accessToken());
            assertEquals(2, f.mock.count("/token"));
        }
    }

    @Test
    void enormousLegalRetryAfterRemainsPositiveAndCannotWrapIntoAnImmediateExchange() throws Exception {
        try (var f = new LifecycleFixture()) {
            String retryAfter = "9".repeat(200);
            f.mock.route("/token", exchange -> {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
                HttpMock.respond(exchange, 429, "{}");
            });
            var failure = assertThrows(TokenFailure.class, f::get);
            assertEquals("TOKEN_RATE_LIMITED", failure.code());
            assertTrue(failure.retryable());
            assertEquals(List.of(retryAfter), f.cache.cooldownRetryAfter(f.key));
            assertEquals(Duration.ofNanos(Long.MAX_VALUE), f.cache.cooldownRemaining(f.key));
            f.advance(Duration.ofDays(365));
            assertEquals(Duration.ofNanos(Long.MAX_VALUE), f.cache.cooldownRemaining(f.key));
            assertEquals("TOKEN_RATE_LIMITED", assertThrows(TokenFailure.class, f::get).code());
            assertEquals(1, f.mock.count("/token"));
            assertEquals(1, f.cache.size());
            assertEquals(0, f.cache.inFlightCount());
        }
    }

    private static final class LifecycleFixture implements AutoCloseable {
        final HttpMock mock = new HttpMock();
        final ControlledHttpClient transport;
        final OAuthTokenClient client;
        final AtomicLong nanos = new AtomicLong();
        final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        final TokenRequest request;
        final TokenKey key;
        final TokenCache cache;

        LifecycleFixture() throws Exception {
            var policy = new OutboundPolicy(new OutboundPolicy.Properties(Set.of("127.0.0.1"), Set.of(443), Set.of("127.0.0.1"),
                    Set.of(mock.uri("/token").getPort())), new MockEnvironment().withProperty("app.environment", "test"));
            transport = new ControlledHttpClient(SystemDefaultDnsResolver.INSTANCE, 1, policy::allowsConnectionAddress);
            client = new OAuthTokenClient(transport, policy);
            request = request("/token");
            key = new TokenKey("test", "tenant-lifecycle", UUID.randomUUID(), "fixture-v1", request.tokenUrl(),
                    request.clientId(), request.clientAuthenticationMethod(), request.scopes());
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return wall.get(); }
            };
            cache = new TokenCache(client, new AuthProperties(null, null, 10, 2, Duration.ofSeconds(1), Duration.ofSeconds(5)),
                    nanos::get, clock, () -> 0.0, metrics);
        }
        TokenRequest request(String path) {
            return new TokenRequest(mock.uri(path).toString(), "lifecycle-client", "fake-lifecycle-secret",
                    "client_secret_basic", List.of("notify"), null);
        }
        CachedToken get() throws Exception { return cache.get(key, request, Duration.ofSeconds(4), Duration.ofSeconds(10), null); }
        void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); wall.updateAndGet(value -> value.plus(duration)); }
        void awaitWaiters(int count) {
            await(() -> {
                var counter = metrics.find("notification.oauth.cache").tag("result", "WAIT").counter();
                return counter != null && counter.count() >= count;
            }, "Expected waiters to join the actual shared future");
        }
        @Override public void close() throws Exception {
            try { cache.close(); }
            finally { try { transport.close(); } finally { mock.close(); metrics.close(); } }
        }
    }

    private static void await(BooleanSupplier condition, String message) {
        long bound = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < bound) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
        fail(message);
    }
}
