package com.rc.notification.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CP06 TC-AUTH-03/04/05/08/11: real OAuth exchange and production cache, with only its clock controlled. */
@Timeout(20)
class TokenCacheTest {
    @Test
    void fiftyConcurrentCallersShareOneNetworkExchangeAndOneGeneration() throws Exception {
        var release = new CountDownLatch(1); var arrived = new CountDownLatch(1);
        try (var f = new CacheFixture(1000)) {
            f.protocol.mock.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); HttpMock.respond(e, 200, OAuthFixture.response("singleflight-token", 3600)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            var executor = Executors.newFixedThreadPool(50); var entered = new CountDownLatch(50); var start = new CountDownLatch(1);
            try {
                var results = new ArrayList<java.util.concurrent.Future<CachedToken>>();
                for (int n = 0; n < 50; n++) results.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS)); entered.countDown(); return f.get();
                }));
                start.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(arrived.await(2, TimeUnit.SECONDS));
                f.awaitWaiters(49);
                assertEquals(1, f.protocol.mock.journal().size()); assertEquals(1, f.cache.inFlightCount());
                release.countDown();
                var first = results.get(0).get(5, TimeUnit.SECONDS);
                for (var future : results) assertEquals(first.generation(), future.get(5, TimeUnit.SECONDS).generation());
                assertEquals(first.generation(), f.get().generation()); assertEquals(1, f.protocol.mock.journal().size());
                assertEquals(0, f.cache.inFlightCount());
            } finally { start.countDown(); release.countDown(); executor.shutdownNow(); }
        } finally { release.countDown(); }
    }

    @Test
    void safetyBoundaryUsesMonotonicAgeAndRefreshesAtSixtySecondsRemaining() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("clock-token-" + calls.incrementAndGet(), 3600)));
            var original = f.get();
            f.wall.set(f.wall.get().plus(Duration.ofDays(7)));
            assertEquals(original.generation(), f.get().generation(), "Wall-clock jumps must not expire a process-local token");
            f.advance(Duration.ofSeconds(3539));
            assertEquals(original.generation(), f.get().generation());
            f.advance(Duration.ofSeconds(1));
            var refreshed = f.get();
            assertNotEquals(original.generation(), refreshed.generation()); assertEquals(2, calls.get());
            assertTrue(refreshed.expiresAt().isAfter(refreshed.receivedAt()));
        }
    }

    @Test
    void plannedBusinessTimeAndSafetyMarginCanRequireEarlierRefreshWithoutAnInfiniteLoop() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("short-token-" + calls.incrementAndGet(), 15)));
            var first = f.cache.get(f.key, f.request, Duration.ofSeconds(3), Duration.ofSeconds(1), null);
            f.advance(Duration.ofSeconds(4)); // Eleven seconds remain; a ten-second request plus margin needs twelve.
            var next = f.cache.get(f.key, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), null);
            assertNotEquals(first.generation(), next.generation()); assertEquals(2, calls.get());
        }
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("unusable-token", 2)));
            var failure = assertThrows(TokenFailure.class, f::get);
            assertEquals("TOKEN_LIFETIME_INCOMPATIBLE", failure.code()); assertFalse(failure.retryable());
            assertEquals(1, f.protocol.mock.journal().size());
        }
    }

    @Test
    void allAuthorizationContextDimensionsAreIsolatedButEquivalentScopeSetsReuse() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new CacheFixture(20)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("isolated-token-" + calls.incrementAndGet(), 3600)));
            f.protocol.mock.route("/other-token", e -> HttpMock.respond(e, 200, OAuthFixture.response("isolated-token-" + calls.incrementAndGet(), 3600)));
            var original = f.get();
            var equivalent = new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(),
                    f.request.clientId(), f.request.clientAuthenticationMethod(), List.of("write", "read", "read"));
            assertEquals(f.key, equivalent);
            assertEquals(original.generation(), f.cache.get(equivalent, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), null).generation());
            var keys = List.of(
                    new TokenKey("other-env", "tenant-a", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-b", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", UUID.randomUUID(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v2", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v1", f.protocol.mock.uri("/other-token").toString(), f.request.clientId(), "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(), "other-client", "client_secret_basic", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_post", List.of("read", "write")),
                    new TokenKey("test", "tenant-a", f.key.authProfileVersionId(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read")));
            var values = new java.util.HashSet<String>(); values.add(original.accessToken());
            for (var key : keys) {
                var request = new TokenRequest(key.tokenUrl(), key.clientId(), "secret-for-" + key.credentialVersion(), key.clientAuthMethod(), key.normalizedScopes(), null);
                var result = f.cache.get(key, request, Duration.ofSeconds(3), Duration.ofSeconds(10), null);
                assertTrue(values.add(result.accessToken()));
            }
            assertEquals(9, calls.get()); assertEquals(9, f.cache.size());
        }
    }

    @Test
    void fiftyFailedWaitersDoNotSeriallyHammerTheEndpointAndCooldownCanRecover() throws Exception {
        var status = new AtomicInteger(503); var release = new CountDownLatch(1); var arrived = new CountDownLatch(1);
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); HttpMock.respond(e, status.get(), OAuthFixture.response("after-failure-token", 3600)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            var executor = Executors.newFixedThreadPool(50); var entered = new CountDownLatch(50); var start = new CountDownLatch(1);
            try {
                var results = new ArrayList<java.util.concurrent.Future<String>>();
                for (int n = 0; n < 50; n++) results.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS)); entered.countDown();
                    return assertThrows(TokenFailure.class, f::get).code();
                }));
                start.countDown(); assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(arrived.await(2, TimeUnit.SECONDS));
                f.awaitWaiters(49); release.countDown();
                for (var result : results) assertEquals("TOKEN_SERVER_ERROR", result.get(5, TimeUnit.SECONDS));
                assertEquals(1, f.protocol.mock.journal().size()); assertEquals(0, f.cache.inFlightCount());
                assertTrue(f.cache.cooldownRemaining(f.key).compareTo(Duration.ZERO) > 0);
                assertEquals("TOKEN_SERVER_ERROR", assertThrows(TokenFailure.class, f::get).code());
                assertEquals(1, f.protocol.mock.journal().size());
                f.advance(Duration.ofSeconds(6)); status.set(200);
                assertEquals("after-failure-token", f.get().accessToken()); assertEquals(2, f.protocol.mock.journal().size());
            } finally { start.countDown(); release.countDown(); executor.shutdownNow(); }
        } finally { release.countDown(); }
    }

    @Test
    void anIndividualWaiterTimeoutDoesNotCancelTheSharedLeader() throws Exception {
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); HttpMock.respond(e, 200, OAuthFixture.response("shared-after-timeout", 3600)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            var executor = Executors.newSingleThreadExecutor();
            try {
                var leaderWaiter = executor.submit(f::get); assertTrue(arrived.await(2, TimeUnit.SECONDS));
                var failure = assertThrows(TokenFailure.class, () -> f.cache.get(f.key, f.request, Duration.ofMillis(50), Duration.ofSeconds(10), null));
                assertEquals("TOKEN_TIMEOUT", failure.code()); assertFalse(leaderWaiter.isDone());
                assertEquals(1, f.cache.inFlightCount()); assertEquals(1, f.protocol.mock.journal().size());
                release.countDown(); var token = leaderWaiter.get(4, TimeUnit.SECONDS);
                assertEquals(token.generation(), f.get().generation()); assertEquals(1, f.protocol.mock.journal().size());
            } finally { release.countDown(); executor.shutdownNow(); }
        } finally { release.countDown(); }
    }

    @Test
    void fullCacheDoesNotEvictAnInflightEntryAndExpiredEntriesCanBeReclaimed() throws Exception {
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var f = new CacheFixture(1)) {
            f.protocol.mock.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); HttpMock.respond(e, 200, OAuthFixture.response("capacity-token", 3600)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            var executor = Executors.newSingleThreadExecutor();
            var other = new TokenKey("test", "tenant-b", UUID.randomUUID(), "v1", f.request.tokenUrl(), f.request.clientId(), "client_secret_basic", List.of("read", "write"));
            try {
                var first = executor.submit(f::get); assertTrue(arrived.await(2, TimeUnit.SECONDS));
                assertEquals("TOKEN_CAPACITY", assertThrows(TokenFailure.class,
                        () -> f.cache.get(other, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), null)).code());
                assertEquals(1, f.cache.size()); assertEquals(1, f.cache.inFlightCount()); assertFalse(first.isDone());
                release.countDown(); first.get(4, TimeUnit.SECONDS); f.advance(Duration.ofSeconds(3601));
                assertEquals("capacity-token", f.cache.get(other, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), null).accessToken());
                assertEquals(1, f.cache.size()); assertEquals(2, f.protocol.mock.journal().size());
            } finally { release.countDown(); executor.shutdownNow(); }
        } finally { release.countDown(); }
    }

    @Test
    void delayedOld401CannotInvalidateANewerGenerationAndRejectedValuesCannotBeReused() throws Exception {
        var calls = new AtomicInteger();
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("generation-token-" + calls.incrementAndGet(), 3600)));
            var old = f.get(); f.advance(Duration.ofSeconds(3540)); var current = f.get();
            assertFalse(f.cache.invalidate(f.key, old.generation()));
            assertEquals(current.generation(), f.cache.get(f.key, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), old.digest()).generation());
            assertEquals(2, calls.get());
        }
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("same-rejected-token", 3600)));
            var rejected = f.get(); assertTrue(f.cache.invalidate(f.key, rejected.generation()));
            var failure = assertThrows(TokenFailure.class, () -> f.cache.get(f.key, f.request, Duration.ofSeconds(3), Duration.ofSeconds(10), rejected.digest()));
            assertEquals("AUTH_REFRESH_REJECTED", failure.code()); assertFalse(failure.retryable());
            assertEquals(2, f.protocol.mock.journal().size(), "A new generation with the same token value must not trigger an unbounded fetch loop");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void leadersPrivateRejectionOrDurationConstraintDoesNotPoisonAnotherWaitersSharedToken(boolean rejectionConstraint) throws Exception {
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var f = new CacheFixture(10)) {
            f.protocol.mock.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); HttpMock.respond(e, 200, OAuthFixture.response("shared-valid-token", rejectionConstraint ? 3600 : 8)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { e.close(); }
            });
            byte[] rejected = rejectionConstraint ? java.security.MessageDigest.getInstance("SHA-256")
                    .digest("shared-valid-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null;
            var executor = Executors.newFixedThreadPool(2);
            try {
                var leader = executor.submit(() -> assertThrows(TokenFailure.class, () -> f.cache.get(f.key, f.request,
                        Duration.ofSeconds(4), Duration.ofSeconds(10), rejected)).code());
                assertTrue(arrived.await(2, TimeUnit.SECONDS));
                var allowedWaiter = executor.submit(() -> f.cache.get(f.key, f.request, Duration.ofSeconds(4), Duration.ofSeconds(1), null));
                f.awaitWaiters(1); release.countDown();
                assertEquals(rejectionConstraint ? "AUTH_REFRESH_REJECTED" : "TOKEN_LIFETIME_INCOMPATIBLE", leader.get(4, TimeUnit.SECONDS));
                assertEquals("shared-valid-token", allowedWaiter.get(4, TimeUnit.SECONDS).accessToken());
                assertEquals(1, f.protocol.mock.journal().size());
            } finally { release.countDown(); executor.shutdownNow(); }
        } finally { release.countDown(); }
    }

    static final class CacheFixture implements AutoCloseable {
        final OAuthTokenProtocolTest.ProtocolFixture protocol = new OAuthTokenProtocolTest.ProtocolFixture();
        final AtomicLong nanos = new AtomicLong();
        final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
        final TokenRequest request = protocol.request("client_secret_basic", null);
        final TokenKey key = new TokenKey("test", "tenant-a", UUID.randomUUID(), "v1", request.tokenUrl(), request.clientId(), request.clientAuthenticationMethod(), request.scopes());
        final TokenCache cache;
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        CacheFixture(int capacity) throws Exception {
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return wall.get(); }
            };
            cache = new TokenCache(protocol.client, new AuthProperties(null, null, capacity, 10, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                    nanos::get, clock, () -> 0.0, metrics);
        }
        CachedToken get() throws Exception { return cache.get(key, request, Duration.ofSeconds(4), Duration.ofSeconds(10), null); }
        void advance(Duration time) { nanos.addAndGet(time.toNanos()); wall.set(wall.get().plus(time)); }
        void awaitWaiters(int minimum) {
            long bound = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < bound) {
                var counter = metrics.find("notification.oauth.cache").tag("result", "WAIT").counter();
                if (counter != null && counter.count() >= minimum) return;
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
            }
            fail("Expected " + minimum + " actual waiters to join the same in-flight future before releasing Token response");
        }
        @Override public void close() throws Exception { try { cache.close(); } finally { protocol.close(); } }
    }
}
