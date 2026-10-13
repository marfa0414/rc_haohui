package com.rc.notification.retry;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.delivery.BusinessHttpSender.FailureKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CP05 TC-RETRY-05/06/07: deterministic policy and overflow edges, with no network or clock sleeps. */
class RetryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"200,SUCCESS", "204,SUCCESS", "299,SUCCESS", "301,FAIL", "302,FAIL", "307,FAIL", "308,FAIL",
            "400,FAIL", "401,FAIL", "403,FAIL", "404,FAIL", "409,FAIL", "408,RETRY", "429,RETRY",
            "500,RETRY", "501,RETRY", "502,RETRY", "503,RETRY", "504,RETRY", "599,RETRY"})
    void followsTheFrozenHttpClassification(int status, String expected) {
        assertEquals(expected, new RetryPolicy(new RetryProperties(null, null)).classify(status, null).name());
    }

    @Test
    void networkUncertaintyRetriesAndRejectedTargetsDoNot() {
        var policy = new RetryPolicy(new RetryProperties(null, null));
        assertEquals("RETRY", policy.classify(null, FailureKind.TIMEOUT).name());
        assertEquals("RETRY", policy.classify(null, FailureKind.NETWORK).name());
        assertEquals("FAIL", policy.classify(null, FailureKind.TARGET_REJECTED).name());
        assertEquals("RETRY", policy.classify(null, FailureKind.CAPACITY).name());
        assertEquals("FAIL", policy.classify(null, FailureKind.CANCELLED).name());
    }

    @Test
    void productionDefaultsAndFullJitterIncludeZeroAndRespectExponentialCaps() {
        var defaults = new RetryProperties(null, null);
        assertEquals(Duration.ofSeconds(30), defaults.baseDelay());
        assertEquals(Duration.ofHours(1), defaults.maxDelay());
        var zero = new RetryPolicy(defaults, () -> 0.0);
        var top = new RetryPolicy(defaults, () -> 1.0);
        for (int attempt : new int[]{1, 2, 7, 8, 12, 63, 10000, Integer.MAX_VALUE}) {
            assertEquals(Duration.ZERO, zero.backoff(attempt));
            long expected = attempt >= 8 ? 3600 : 30L * (1L << (attempt - 1));
            assertEquals(Duration.ofSeconds(expected), top.backoff(attempt));
        }
        var values = new double[]{0.0, 0.25, 0.5, 0.75, 1.0};
        var index = new AtomicInteger();
        var samples = new RetryPolicy(defaults, () -> values[index.getAndIncrement()]);
        var distinct = new java.util.HashSet<Duration>();
        for (int i = 0; i < values.length; i++) {
            var delay = samples.backoff(1);
            assertFalse(delay.isNegative());
            assertTrue(delay.compareTo(Duration.ofSeconds(30)) <= 0);
            distinct.add(delay);
        }
        assertEquals(values.length, distinct.size(), "The strategy must actually use its random source");
    }

    static Stream<Arguments> retryAfterCases() {
        return Stream.of(Arguments.of("0", NOW), Arguments.of("30", NOW.plusSeconds(30)),
                Arguments.of("999999999999999999999999999999999999999", Instant.MAX),
                Arguments.of("Tue, 13 Oct 2026 00:01:00 GMT", NOW.plusSeconds(60)),
                Arguments.of("Tuesday, 13-Oct-26 00:01:00 GMT", NOW.plusSeconds(60)),
                Arguments.of("Tue Oct 13 00:01:00 2026", NOW.plusSeconds(60)),
                Arguments.of("Mon, 12 Oct 2026 23:59:00 GMT", NOW));
    }

    @ParameterizedTest @MethodSource("retryAfterCases")
    void parsesValidRetryAfterWithoutShorteningTheServersMinimum(String value, Instant expected) {
        assertEquals(expected, RetryAfter.parse(value, NOW).orElseThrow());
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "1.5", "nonsense", "Tue, 99 Oct 2026 00:01:00 GMT", "+30"})
    void invalidRetryAfterFallsBackToOrdinaryBackoff(String value) {
        assertTrue(RetryAfter.parse(value, NOW).isEmpty());
        var policy = new RetryPolicy(Duration.ofSeconds(30), Duration.ofHours(1), () -> 1.0);
        assertEquals(NOW.plusSeconds(30), policy.nextAttemptAt(NOW, 1, Map.of("Retry-After", List.of(value))));
    }

    @Test
    void duplicateHeadersUseTheLargestValidLowerBoundEvenAboveLocalCap() {
        var policy = new RetryPolicy(Duration.ofSeconds(30), Duration.ofHours(1), () -> 1.0);
        assertEquals(NOW.plusSeconds(7200), policy.nextAttemptAt(NOW, 100,
                Map.of("rEtRy-AfTeR", List.of("invalid", "60", "7200", "-10"))));
        assertEquals(Instant.MAX, policy.nextAttemptAt(NOW, 1,
                Map.of("retry-after", List.of("999999999999999999999999999999999999999"))));
        assertEquals(NOW.plusSeconds(30), policy.nextAttemptAt(NOW, 1, Map.of()));
    }

    @Test
    void obsoleteDatesResolveTheFiftyYearWindowBeforeWeekdayAndLeapDayValidation() {
        assertEquals(NOW, RetryAfter.parse("Sunday, 06-Nov-94 08:49:37 GMT", NOW).orElseThrow());
        assertEquals(NOW, RetryAfter.parse("Sun Nov  6 08:49:37 1994", NOW).orElseThrow());
        var future = Instant.parse("2050-01-01T00:00:00Z");
        assertEquals(future, RetryAfter.parse("Tuesday, 29-Feb-00 08:49:37 GMT", future).orElseThrow());
    }
}
