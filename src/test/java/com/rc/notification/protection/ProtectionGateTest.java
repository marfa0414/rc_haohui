package com.rc.notification.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.delivery.BusinessHttpSender.FailureKind;
import com.rc.notification.worker.Lease;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CP07 TC-GUARD-01/03/04/06/07/08: production Resilience4j gate with controlled monotonic time. */
@Timeout(15)
class ProtectionGateTest {
    static Stream<Arguments> quotaFallbackCases() {
        return Stream.of(Arguments.of(List.of(), 30L), Arguments.of(List.of("invalid"), 30L),
                Arguments.of(List.of("-1"), 30L), Arguments.of(List.of("0"), 0L), Arguments.of(List.of("1"), 1L),
                Arguments.of(List.of("Sun, 06 Nov 1994 08:49:37 GMT"), 0L), Arguments.of(List.of("bad", "3", "1"), 3L));
    }
    @ParameterizedTest @MethodSource("quotaFallbackCases")
    void defaultThirtySecondFallbackDoesNotOverrideAnyLegalRetryAfter(List<String> values, long expected) {
        try (var f = new Fixture(defaults())) {
            var permission = f.gate.acquire(f.lease); assertTrue(permission.admitted());
            assertEquals(Duration.ofSeconds(expected), f.gate.recordQuota429(f.lease, values));
            permission.permit().complete(429, null, Duration.ZERO);
            assertEquals(Duration.ofSeconds(expected), f.gate.cooldown(f.lease));
            assertEquals(0, f.snapshot().bufferedCalls());
        }
    }
    @Test void aLaterZeroRetryAfterCannotShortenAnAlreadyEstablishedQuotaCooldown() {
        try (var f = new Fixture(defaults())) {
            var first = f.gate.acquire(f.lease); var concurrent = f.gate.acquire(f.lease);
            assertTrue(first.admitted()); assertTrue(concurrent.admitted());
            assertEquals(Duration.ofSeconds(30), f.gate.recordQuota429(f.lease, List.of("invalid")));
            first.permit().complete(429, null, Duration.ZERO); f.advance(Duration.ofSeconds(1));
            assertEquals(Duration.ofSeconds(29), f.gate.recordQuota429(f.lease, List.of("0")));
            concurrent.permit().complete(429, null, Duration.ZERO);
        }
    }
    static ProtectionProperties defaults() { return new ProtectionProperties(null, null, null, null, null, null, null, null, null, null, null, null); }
    static Lease lease(String provider, String endpoint, String tenant, UUID auth) {
        return new Lease(UUID.randomUUID(), 1, UUID.randomUUID(), "gate-test", Instant.parse("2030-01-01T00:00:00Z"),
                tenant, "notify", "gate-event", provider, endpoint, auth,
                new RequestSnapshot("https://supplier.invalid/notify", "POST", Map.of(), "application/json", new byte[0]));
    }

    @Test
    void defaultsAndMinimumTenSamplesRequireAnActualFiftyPercentFailureRate() {
        var p = defaults(); assertEquals(20, p.slidingWindowSize()); assertEquals(10, p.minimumCalls());
        assertEquals(50f, p.failureRateThreshold()); assertEquals(Duration.ofSeconds(30), p.openDuration()); assertEquals(1, p.halfOpenPermits()); assertEquals(Duration.ofSeconds(30), p.quotaCooldown());
        try (var f = new Fixture(p)) {
            for (int n = 0; n < 5; n++) f.record(503, null);
            for (int n = 0; n < 4; n++) f.record(200, null);
            var nine = f.snapshot(); assertEquals("CLOSED", nine.state()); assertEquals(9, nine.bufferedCalls()); assertEquals(5, nine.failedCalls());
            f.record(200, null);
            assertEquals("OPEN", f.snapshot().state()); assertEquals(10, f.snapshot().bufferedCalls());
            var denied = f.gate.acquire(f.lease); assertFalse(denied.admitted()); assertTrue(denied.delay().compareTo(Duration.ZERO) > 0);
            var anotherEndpoint = lease("provider", "other", "tenant-a", f.lease.authProfileId());
            try (var permit = f.gate.acquire(anotherEndpoint).permit()) { assertNotNull(permit); permit.complete(200, null, Duration.ofMillis(1)); }
            assertEquals("CLOSED", f.gate.circuit(anotherEndpoint).orElseThrow().state());
        }
        try (var f = new Fixture(p)) {
            for (int n = 0; n < 4; n++) f.record(503, null);
            for (int n = 0; n < 6; n++) f.record(200, null);
            assertEquals("CLOSED", f.snapshot().state()); assertEquals(10, f.snapshot().bufferedCalls()); assertEquals(4, f.snapshot().failedCalls());
        }
    }

    static Stream<Arguments> exclusions() {
        return Stream.of(Arguments.of(301, null), Arguments.of(400, null), Arguments.of(401, null), Arguments.of(403, null),
                Arguments.of(408, null), Arguments.of(429, null), Arguments.of(null, FailureKind.CAPACITY),
                Arguments.of(null, FailureKind.CANCELLED), Arguments.of(null, FailureKind.TARGET_REJECTED), Arguments.of(null, null));
    }
    @ParameterizedTest @MethodSource("exclusions")
    void excludedOutcomesReleaseThePermitWithoutAddingEitherASuccessOrAFailure(Integer status, FailureKind failure) {
        try (var f = new Fixture(defaults())) {
            f.record(status, failure);
            assertEquals(0, f.snapshot().bufferedCalls()); assertEquals(0, f.snapshot().failedCalls()); assertEquals(0, f.snapshot().activePermits());
            assertEquals("CLOSED", f.snapshot().state());
        }
    }

    @Test
    void eligibleNetworkTimeoutAndServerFailuresCountExactlyOnceDespiteRepeatedCompletionAndClose() {
        try (var f = new Fixture(defaults())) {
            for (var failure : List.of(FailureKind.NETWORK, FailureKind.TIMEOUT)) f.record(null, failure);
            var acquired = f.gate.acquire(f.lease); assertTrue(acquired.admitted());
            acquired.permit().complete(503, null, Duration.ofMillis(1)); acquired.permit().complete(200, null, Duration.ofMillis(1)); acquired.permit().close();
            assertEquals(3, f.snapshot().bufferedCalls()); assertEquals(3, f.snapshot().failedCalls()); assertEquals(0, f.snapshot().activePermits());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void fiftyConcurrentHalfOpenContendersHaveExactlyOneProbeAndItsResultControlsTransition(boolean success) throws Exception {
        try (var f = new Fixture(defaults())) {
            f.open(); f.advance(Duration.ofSeconds(30).plusNanos(1));
            var executor = Executors.newFixedThreadPool(50); var start = new CountDownLatch(1);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<ProtectionGate.Admission>>();
                for (int n = 0; n < 50; n++) futures.add(executor.submit(() -> { assertTrue(start.await(3, TimeUnit.SECONDS)); return f.gate.acquire(f.lease); }));
                start.countDown(); var admitted = new ArrayList<ProtectionGate.Admission>();
                for (var future : futures) { var result = future.get(3, TimeUnit.SECONDS); if (result.admitted()) admitted.add(result); }
                assertEquals(1, admitted.size()); assertEquals("HALF_OPEN", f.snapshot().state()); assertEquals(1, f.snapshot().activePermits());
                admitted.get(0).permit().complete(success ? 200 : 503, null, Duration.ofMillis(1));
                assertEquals(success ? "CLOSED" : "OPEN", f.snapshot().state()); assertEquals(0, f.snapshot().activePermits());
            } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
        }
    }

    @Test
    void abandonedHalfOpenProbeAndExcludedResultBothAllowTheNextProbe() {
        try (var f = new Fixture(defaults())) {
            f.open(); f.advance(Duration.ofSeconds(30).plusNanos(1));
            var abandoned = f.gate.acquire(f.lease); assertTrue(abandoned.admitted()); abandoned.permit().close();
            assertEquals("HALF_OPEN", f.snapshot().state()); assertEquals(0, f.snapshot().activePermits());
            var excluded = f.gate.acquire(f.lease); assertTrue(excluded.admitted()); excluded.permit().complete(401, null, Duration.ZERO);
            assertEquals(0, f.snapshot().bufferedCalls()); assertEquals(0, f.snapshot().activePermits());
            var success = f.gate.acquire(f.lease); assertTrue(success.admitted()); success.permit().complete(204, null, Duration.ZERO);
            assertEquals("CLOSED", f.snapshot().state());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aLateClosedGenerationCannotCompleteOrReleaseTheNewHalfOpenProbe(boolean completeBeforeClose) {
        try (var f = new Fixture(defaults())) {
            var late = f.gate.acquire(f.lease); assertTrue(late.admitted());
            f.open(); f.advance(Duration.ofSeconds(30).plusNanos(1));
            var probe = f.gate.acquire(f.lease); assertTrue(probe.admitted());
            long generation = f.snapshot().generation();
            if (completeBeforeClose) late.permit().complete(503, null, Duration.ofSeconds(31));
            late.permit().close();
            assertEquals("HALF_OPEN", f.snapshot().state()); assertEquals(generation, f.snapshot().generation());
            assertEquals(1, f.snapshot().activePermits()); assertEquals(0, f.snapshot().bufferedCalls());
            assertFalse(f.gate.acquire(f.lease).admitted());
            probe.permit().complete(200, null, Duration.ZERO); assertEquals("CLOSED", f.snapshot().state());
        }
    }

    @Test
    void wallClockJumpsNeitherAdvanceOpenPermissionNorChangeAnEstablishedQuotaWait() {
        try (var f = new Fixture(defaults())) {
            var quotaDomain = lease("quota-provider", "endpoint", "tenant-a", UUID.randomUUID());
            var quotaPermission = f.gate.acquire(quotaDomain); assertTrue(quotaPermission.admitted());
            assertEquals(Duration.ofSeconds(120), f.gate.recordQuota429(quotaDomain, List.of("120")));
            quotaPermission.permit().complete(429, null, Duration.ZERO);
            f.open();
            for (Duration jump : List.of(Duration.ofDays(365), Duration.ofDays(-730))) {
                f.wall.updateAndGet(now -> now.plus(jump)); // Deliberately leave the monotonic clock unchanged.
                var denied = f.gate.acquire(f.lease);
                assertFalse(denied.admitted()); assertEquals(Duration.ofSeconds(30), denied.delay());
                assertEquals("OPEN", f.snapshot().state()); assertEquals(Duration.ofSeconds(120), f.gate.cooldown(quotaDomain));
            }
            f.nanos.addAndGet(Duration.ofSeconds(30).plusNanos(1).toNanos());
            var probe = f.gate.acquire(f.lease); assertTrue(probe.admitted());
            assertEquals("HALF_OPEN", f.snapshot().state());
            assertEquals(Duration.ofSeconds(90).minusNanos(1), f.gate.cooldown(quotaDomain));
            probe.permit().close();
        }
    }

    @Test
    void explicitDatabaseTimeDefinesHttpDateCooldownEvenWhenTheProcessWallClockIsFarAhead() {
        try (var f = new Fixture(defaults())) {
            Instant databaseNow = Instant.parse("2020-01-01T00:00:00Z");
            f.wall.set(Instant.parse("2030-01-01T00:00:00Z"));
            String header = DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", java.util.Locale.US)
                    .withZone(ZoneOffset.UTC).format(databaseNow.plusSeconds(120));
            var permission = f.gate.acquire(f.lease); assertTrue(permission.admitted());
            assertEquals(Duration.ofSeconds(120), f.gate.recordQuota429(f.lease, List.of(header), databaseNow));
            permission.permit().complete(429, null, Duration.ZERO);
            assertEquals(Duration.ofSeconds(120), f.gate.cooldown(f.lease));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quotaLowerBoundIsSeparateFromCircuitAndFromOtherAuthenticationContexts(boolean date) {
        try (var f = new Fixture(defaults())) {
            var other = lease("provider", "endpoint", "tenant-a", UUID.randomUUID());
            var acquired = f.gate.acquire(f.lease); assertTrue(acquired.admitted());
            String value = date ? com.rc.notification.support.HttpMock.httpDate(f.wall.get().plusSeconds(120)) : "120";
            assertEquals(Duration.ofSeconds(120), f.gate.recordQuota429(f.lease, List.of(value)));
            acquired.permit().complete(429, null, Duration.ZERO);
            assertEquals(0, f.snapshot().bufferedCalls()); assertEquals("CLOSED", f.snapshot().state());
            assertFalse(f.gate.acquire(f.lease).admitted());
            try (var unaffected = f.gate.acquire(other).permit()) { assertNotNull(unaffected); unaffected.complete(200, null, Duration.ZERO); }
            f.advance(Duration.ofSeconds(119)); assertEquals(Duration.ofSeconds(1), f.gate.cooldown(f.lease));
            assertFalse(f.gate.acquire(f.lease).admitted()); f.advance(Duration.ofSeconds(1));
            var recovered = f.gate.acquire(f.lease); assertTrue(recovered.admitted()); recovered.permit().close();
        }
    }

    @Test
    void enormousLegalQuotaDelayRemainsPositiveAndExplicitMappingCanShareOnlyChosenContexts() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var p = new ProtectionProperties(null, null, null, null, null, null, null, null, null, null, null,
                List.of(new ProtectionProperties.QuotaMapping("tenant-a", a, "shared"), new ProtectionProperties.QuotaMapping("tenant-b", b, "shared")));
        try (var f = new Fixture(p)) {
            var first = lease("provider", "endpoint", "tenant-a", a); var second = lease("provider", "endpoint", "tenant-b", b);
            var admitted = f.gate.acquire(first); assertTrue(admitted.admitted());
            assertTrue(f.gate.recordQuota429(first, List.of("9".repeat(200))).compareTo(Duration.ofDays(365)) > 0);
            admitted.permit().complete(429, null, Duration.ZERO);
            f.advance(Duration.ofDays(365)); assertFalse(f.gate.acquire(first).admitted()); assertFalse(f.gate.acquire(second).admitted());
            assertTrue(f.gate.cooldown(second).compareTo(Duration.ZERO) > 0);
            var unrelated = f.gate.acquire(f.lease); assertTrue(unrelated.admitted()); unrelated.permit().close();
        }
    }

    static final class Fixture implements AutoCloseable {
        final AtomicLong nanos = new AtomicLong(); final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2026-10-13T00:00:00Z"));
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry(); final ProtectionGate gate;
        final Lease lease = lease("provider", "endpoint", "tenant-a", UUID.randomUUID());
        Fixture(ProtectionProperties p) {
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return wall.get(); }
            };
            gate = new ProtectionGate(p, "test", metrics, clock, nanos::get, () -> 0);
        }
        void record(Integer status, FailureKind failure) { var admission = gate.acquire(lease); assertTrue(admission.admitted()); admission.permit().complete(status, failure, Duration.ZERO); }
        void open() { for (int n = 0; n < 10; n++) record(503, null); assertEquals("OPEN", snapshot().state()); }
        ProtectionGate.CircuitSnapshot snapshot() { return gate.circuit(lease).orElseThrow(); }
        void advance(Duration d) { nanos.addAndGet(d.toNanos()); wall.updateAndGet(v -> v.plus(d)); }
        @Override public void close() { metrics.close(); }
    }
}
