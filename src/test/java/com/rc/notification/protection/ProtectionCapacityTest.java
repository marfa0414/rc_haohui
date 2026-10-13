package com.rc.notification.protection;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CP07 TC-GUARD-08: bounded domain maps never evict active permits or active quota cooldown. */
class ProtectionCapacityTest {
    @Test void unusedOpenDomainsNeedBothTheirWaitAndIdleTtlBeforeTheyCanBeReclaimed() {
        var p = new ProtectionProperties(null, null, null, Duration.ofSeconds(30), null, null, Duration.ZERO, 1, 2, Duration.ofSeconds(1), null, null);
        try (var f = new ProtectionGateTest.Fixture(p)) {
            f.open(); f.advance(Duration.ofSeconds(2));
            var other = ProtectionGateTest.lease("new-provider", "endpoint", "tenant-b", UUID.randomUUID());
            assertFalse(f.gate.acquire(other).admitted(), "Idle TTL cannot shorten the active OPEN wait");
            assertEquals("OPEN", f.snapshot().state());
            f.advance(Duration.ofSeconds(29));
            var replacement = f.gate.acquire(other); assertTrue(replacement.admitted()); replacement.permit().close();
            assertTrue(f.gate.circuit(f.lease).isEmpty()); assertEquals(1, f.gate.circuitEntryCount());
        }
    }
    @Test void abandonedHalfOpenDomainsCanBeReclaimedAfterIdleTtlButLiveProbesRemainPinned() {
        var p = new ProtectionProperties(null, null, null, Duration.ofSeconds(1), null, null, Duration.ZERO, 1, 2, Duration.ofSeconds(1), null, null);
        try (var f = new ProtectionGateTest.Fixture(p)) {
            f.open(); f.advance(Duration.ofSeconds(1).plusNanos(1));
            var probe = f.gate.acquire(f.lease); assertTrue(probe.admitted());
            var other = ProtectionGateTest.lease("new-provider", "endpoint", "tenant-b", UUID.randomUUID());
            f.advance(Duration.ofSeconds(2)); assertFalse(f.gate.acquire(other).admitted());
            probe.permit().close(); f.advance(Duration.ofSeconds(1).plusNanos(1));
            var replacement = f.gate.acquire(other); assertTrue(replacement.admitted()); replacement.permit().close();
            assertTrue(f.gate.circuit(f.lease).isEmpty()); assertEquals(1, f.gate.circuitEntryCount());
        }
    }
    @Test void activeEntriesStayPinnedAndInactiveClosedEntriesBecomeReclaimableAfterIdleTtl() {
        var p = new ProtectionProperties(null, null, null, null, null, null, Duration.ZERO, 1, 1, Duration.ofSeconds(1), null, null);
        try (var f = new ProtectionGateTest.Fixture(p)) {
            var original = f.gate.acquire(f.lease); assertTrue(original.admitted());
            var other = ProtectionGateTest.lease("other-provider", "endpoint", "tenant-b", UUID.randomUUID());
            f.advance(Duration.ofSeconds(2));
            var denied = f.gate.acquire(other); assertFalse(denied.admitted()); assertEquals("PROTECTION_CAPACITY", denied.reason());
            assertEquals(1, f.gate.circuitEntryCount()); assertEquals(1, f.gate.quotaEntryCount());
            assertEquals(1, f.snapshot().activePermits());
            original.permit().close(); f.advance(Duration.ofSeconds(1).plusNanos(1));
            var replacement = f.gate.acquire(other); assertTrue(replacement.admitted()); replacement.permit().close();
            assertEquals(1, f.gate.circuitEntryCount()); assertTrue(f.gate.quotaEntryCount() <= 1); assertTrue(f.gate.circuit(f.lease).isEmpty());
        }
    }
    @Test void expiredQuotaEntriesCanBeReusedButALiveRetryAfterCannotBeEvicted() {
        var p = new ProtectionProperties(null, null, null, null, null, null, Duration.ZERO, 2, 1, null, Duration.ofMillis(1), null);
        try (var f = new ProtectionGateTest.Fixture(p)) {
            var original = f.gate.acquire(f.lease); assertTrue(original.admitted());
            assertEquals(Duration.ofSeconds(2), f.gate.recordQuota429(f.lease, List.of("2")));
            original.permit().complete(429, null, Duration.ZERO);
            var other = ProtectionGateTest.lease("provider", "endpoint", "tenant-b", UUID.randomUUID());
            assertFalse(f.gate.acquire(other).admitted()); assertEquals(1, f.gate.quotaEntryCount());
            f.advance(Duration.ofSeconds(2));
            var replacement = f.gate.acquire(other); assertTrue(replacement.admitted()); replacement.permit().close();
            assertTrue(f.gate.quotaEntryCount() <= 1);
        }
    }
}
