package com.rc.notification.observability;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.OAuthFixture;
import com.rc.notification.support.RealPostgres;
import com.rc.notification.support.WorkerFixture;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;

/** TC-OBS-02: real HTTP, database, Worker, OAuth and bounded resource observations, never fake meters. */
@Timeout(90)
class OperationalMetricsTest {
    private static Map<String, Object> defaults() {
        var properties = new HashMap<String, Object>(fastRetries());
        properties.put("app.observability.sampling-enabled", "false");
        properties.put("app.oauth.token-timeout", "1s");
        properties.put("app.oauth.cooldown-min", "50ms");
        properties.put("app.oauth.cooldown-max", "50ms");
        properties.put("app.worker.business-timeout", "2s");
        properties.put("app.worker.attempt-timeout", "4s");
        properties.put("app.worker.lease-duration", "8s");
        return properties;
    }

    @Test
    void acceptedCountersRequireCommitAndHttpCountersIncludeSecurityConflictRollbackAndRateLimit() throws Exception {
        var properties = defaults();
        properties.put("app.admission.requests-per-second", "0.001");
        properties.put("app.admission.burst-capacity", "4");
        try (var api = new NotificationApiFixture(new RealPostgres(), "api", properties)) {
            var metrics = api.application.getBean(MeterRegistry.class);
            assertEquals(0, count(metrics, "notification.submission", "result", "ACCEPTED"));
            String first = payload("tenant-a", "user.registered", "metrics-accepted", "{\"value\":\"cp10-private-body\"}");
            assertEquals(401, api.post(first, "cp10-wrong-credential").statusCode());
            assertEquals(403, api.post(payload("tenant-b", "user.registered", "metrics-forbidden", "{}"), CALLER_A).statusCode());
            assertEquals(400, api.post("{malformed", CALLER_A).statusCode());
            var accepted = api.post(first, CALLER_A); assertEquals(202, accepted.statusCode(), accepted.body());
            UUID id = UUID.fromString(api.json(accepted).path("notificationId").asText());
            assertEquals(200, api.post(first, CALLER_A).statusCode());
            assertEquals(409, api.post(payload("tenant-a", "user.registered", "metrics-accepted", "{\"different\":true}"), CALLER_A).statusCode());
            api.sql("CREATE FUNCTION cp10_reject_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'cp10-private-storage'; END $$");
            api.sql("CREATE TRIGGER cp10_reject_insert BEFORE INSERT ON notification_task FOR EACH ROW EXECUTE FUNCTION cp10_reject_insert()");
            var rolledBack = api.post(payload("tenant-a", "user.registered", "metrics-rolled-back", "{}"), CALLER_A);
            assertEquals(503, rolledBack.statusCode()); assertFalse(rolledBack.body().contains("cp10-private-storage"));
            api.sql("DROP TRIGGER cp10_reject_insert ON notification_task");
            assertEquals(429, api.post(payload("tenant-a", "user.registered", "metrics-rate-limited", "{}"), CALLER_A).statusCode());
            assertEquals(1, api.taskCount());
            await(() -> count(metrics, "notification.submission.http", "result", "RATE_LIMITED") == 1);
            assertEquals(1, meter(api, "notification.submission", "COUNT", "result", "ACCEPTED"));
            assertEquals(1, meter(api, "notification.submission", "COUNT", "result", "DEDUPLICATED"));
            for (String result : List.of("ACCEPTED", "DEDUPLICATED", "CONFLICT", "UNAUTHENTICATED", "FORBIDDEN", "INVALID_REQUEST", "UNAVAILABLE", "RATE_LIMITED")) {
                assertEquals(1, meter(api, "notification.submission.http", "COUNT", "result", result), result);
                assertEquals(1, meter(api, "notification.submission.http.duration", "COUNT", "result", result), result);
            }
            assertEquals(1, meter(api, "notification.admission", "COUNT", "result", "RATE_LIMITED"));
            summary("cp10-metrics-admission", Map.of("acceptedTaskIds", List.of(id.toString()), "committed", 1,
                    "deduplicated", 1, "conflicted", 1, "rolledBack", 1, "rateLimited", 1, "rejectedRows", 0));
        }
    }

    @Test
    void globalMetricsRequireAdminAndOnlyFixedMetadataIsExposed() throws Exception {
        try (var fixture = new WorkerFixture(defaults())) {
            fixture.mock.route("/notify", e -> HttpMock.respond(e, 200, "cp10-private-supplier-response"));
            UUID id = fixture.submit("cp10-private-event", "{\"payload\":{\"value\":\"cp10-private-body\"}}");
            assertEquals("succeeded", executeNextAttempt(fixture, id).path("status").asText());
            assertTrue(fixture.api.application.getBean(TaskQueueMetrics.class).refresh());
            for (String path : List.of("/actuator/metrics", "/actuator/metrics/notification.submission")) {
                assertEquals(401, fixture.api.get(path, null).statusCode());
                for (String caller : List.of(CALLER_A, CALLER_B, CONFIG_A, OPERATOR_A, OPERATOR_B)) {
                    assertEquals(403, fixture.api.get(path, caller).statusCode());
                }
            }
            for (String path : List.of("/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/actuator/beans")) {
                assertEquals(404, fixture.api.get(path, ADMIN).statusCode(), path);
            }
            var index = fixture.api.get("/actuator/metrics", ADMIN); assertEquals(200, index.statusCode());
            List<String> outputs = new ArrayList<>(); outputs.add(index.body());
            for (JsonNode name : fixture.api.json(index).path("names")) {
                if (!name.asText().startsWith("notification.")) continue;
                var response = fixture.api.get("/actuator/metrics/" + name.asText(), ADMIN);
                assertEquals(200, response.statusCode(), name.asText()); outputs.add(response.body());
            }
            for (String output : outputs) for (String secret : List.of("cp10-private-event", "cp10-private-body", "cp10-private-supplier-response",
                    "cp04-test-only-secret", "cp06 fake:+密钥", ADMIN, id.toString(), fixture.mock.uri("/notify").toString())) {
                assertFalse(output.contains(secret), "Metric output must contain only fixed labels and aggregates");
            }
            var registry = fixture.api.application.getBean(MeterRegistry.class);
            for (var meter : registry.getMeters()) if (meter.getId().getName().startsWith("notification.")) {
                for (var tag : meter.getId().getTags()) assertTrue(List.of("result", "status", "client", "decision", "phase", "code", "reason", "kind", "state", "from", "to")
                        .contains(tag.getKey()), "Unbounded identity must not enter notification metric labels");
            }
            double before = Instant.now().toEpochMilli() / 1000.0;
            double observed = meter(fixture.api, "notification.clock.epoch.seconds", "VALUE");
            double after = Instant.now().toEpochMilli() / 1000.0;
            assertTrue(observed >= before && observed <= after, "Clock observation must be the application's real wall clock");
            assertTrue(meter(fixture.api, "jvm.memory.used", "VALUE", "area", "heap") > 0);
            assertTrue(meter(fixture.api, "jvm.threads.live", "VALUE") > 0);
            summary("cp10-metrics-authorization", Map.of("acceptedTaskIds", List.of(id.toString()), "adminAllowed", true,
                    "otherRolesDenied", true, "scannedMetricResponses", outputs.size(), "sensitiveMatches", 0));
        }
    }

    @Test
    void committedDecisionsRetriesRateLimitsAndLeaseRecoveryHaveExactIncrements() throws Exception {
        try (var fixture = new WorkerFixture(defaults())) {
            var retry = new AtomicInteger(); var limited = new AtomicInteger();
            fixture.mock.route("/notify", exchange -> {
                String event = exchange.getRequestHeaders().getFirst("X-Event-Id");
                int status = switch (event) {
                    case "metrics-retry" -> retry.incrementAndGet() == 1 ? 500 : 200;
                    case "metrics-failed" -> 400;
                    case "metrics-429" -> limited.incrementAndGet() == 1 ? 429 : 200;
                    default -> 200;
                };
                if (status == 429) exchange.getResponseHeaders().set("Retry-After", "0");
                HttpMock.respond(exchange, status, "ignored");
            });
            var registry = fixture.api.application.getBean(MeterRegistry.class);
            double retryBefore = count(registry, "notification.worker.decision", "decision", "RETRY");
            double successBefore = count(registry, "notification.worker.decision", "decision", "SUCCESS");
            double failedBefore = count(registry, "notification.worker.decision", "decision", "FAIL");
            var first = fixture.submit("metrics-retry", "{}");
            assertEquals("pending", executeNextAttempt(fixture, first).path("status").asText());
            assertEquals("succeeded", executeNextAttempt(fixture, first).path("status").asText());
            var failed = fixture.submit("metrics-failed", "{}");
            assertEquals("failed", executeNextAttempt(fixture, failed).path("status").asText());
            var quota = fixture.submit("metrics-429", "{}");
            assertEquals("pending", executeNextAttempt(fixture, quota).path("status").asText());
            assertEquals("succeeded", executeNextAttempt(fixture, quota).path("status").asText());
            var recovered = fixture.submit("metrics-recovered", "{}");
            var lease = fixture.repository.claim("cp04-provider", "metrics-expired-owner", Duration.ofMillis(300)).orElseThrow();
            assertEquals(recovered, lease.taskId());
            fixture.repository.reserve(lease, Duration.ofMillis(200)).orElseThrow();
            fixture.awaitExpiredLease(recovered, Duration.ofSeconds(3));
            assertEquals(1, fixture.worker.tick()); fixture.awaitStatus(recovered, "succeeded", Duration.ofSeconds(5));
            fixture.awaitIdle(Duration.ofSeconds(5));
            assertEquals(retryBefore + 2, meter(fixture.api, "notification.worker.decision", "COUNT", "decision", "RETRY"));
            assertEquals(successBefore + 3, meter(fixture.api, "notification.worker.decision", "COUNT", "decision", "SUCCESS"));
            assertEquals(failedBefore + 1, meter(fixture.api, "notification.worker.decision", "COUNT", "decision", "FAIL"));
            assertEquals(1, meter(fixture.api, "notification.worker.recovered", "COUNT"));
            assertEquals(1, meter(fixture.api, "notification.protection.quota_limited", "COUNT"));
            assertEquals(4, meter(fixture.api, "notification.submission", "COUNT", "result", "ACCEPTED"));
            assertEquals(6, fixture.mock.journal().size());
            assertEquals(6, meter(fixture.api, "notification.http.duration", "COUNT", "client", "business", "result", "COMPLETED"));
            var sampler = fixture.api.application.getBean(TaskQueueMetrics.class); assertTrue(sampler.refresh());
            assertEquals(3, meter(fixture.api, "notification.tasks", "VALUE", "status", "succeeded"));
            assertEquals(1, meter(fixture.api, "notification.tasks", "VALUE", "status", "failed"));
            assertEquals(0, meter(fixture.api, "notification.tasks", "VALUE", "status", "pending"));
            assertEquals(0, meter(fixture.api, "notification.tasks", "VALUE", "status", "processing"));
            assertEquals(0, meter(fixture.api, "notification.queue.oldest_due.seconds", "VALUE"));
            for (UUID id : List.of(first, failed, quota, recovered)) fixture.evidence("cp10-known-metric-decisions", id, null);
            summary("cp10-known-metric-decisions", Map.of("acceptedTaskIds", List.of(first, failed, quota, recovered).stream().map(UUID::toString).toList(),
                    "successes", 3, "terminalFailures", 1, "retryDecisions", 2, "leaseRecoveries", 1, "physicalRequests", 6));
        }
    }

    @Test
    void tokenFailureAndCacheReuseAreCountedByTheRealSharedExchange() throws Exception {
        var exchanges = new AtomicInteger();
        try (var oauth = new OAuthFixture(defaults(), "client_secret_post")) {
            oauth.tokens.route("/token", e -> HttpMock.respond(e, exchanges.incrementAndGet() == 1 ? 503 : 200,
                    exchanges.get() == 1 ? "cp10-private-token-error" : OAuthFixture.response("cp10-private-token", 3600)));
            oauth.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var first = oauth.submit("metric-token-recovery");
            var failed = executeNextAttempt(oauth.worker, first);
            assertEquals("NOT_SENT", failed.path("last_outcome").asText());
            assertEquals(0, oauth.worker.mock.journal().size());
            assertEquals("succeeded", executeNextAttempt(oauth.worker, first).path("status").asText());
            var second = oauth.submit("metric-token-cache-hit");
            assertEquals("succeeded", executeNextAttempt(oauth.worker, second).path("status").asText());
            assertEquals(2, meter(oauth.worker.api, "notification.oauth.exchange", "COUNT"));
            assertEquals(1, meter(oauth.worker.api, "notification.oauth.failure", "COUNT", "code", "TOKEN_SERVER_ERROR"));
            assertEquals(1, meter(oauth.worker.api, "notification.oauth.cache", "COUNT", "result", "HIT"));
            assertEquals(1, meter(oauth.worker.api, "notification.worker.completion", "COUNT", "result", "NOT_SENT"));
            assertEquals(1, meter(oauth.worker.api, "notification.worker.decision", "COUNT", "decision", "RETRY"));
            assertEquals(2, meter(oauth.worker.api, "notification.http.duration", "COUNT", "client", "token", "result", "COMPLETED"));
            assertEquals(2, oauth.tokens.journal().size()); assertEquals(2, oauth.worker.mock.journal().size());
            assertEquals(0, meter(oauth.worker.api, "notification.oauth.inflight", "VALUE"));
            assertEquals(0, meter(oauth.worker.api, "notification.oauth.leaders.active", "VALUE"));
            for (UUID id : List.of(first, second)) oauth.evidence("cp10-token-metrics", id);
            summary("cp10-token-metrics", Map.of("acceptedTaskIds", List.of(first.toString(), second.toString()),
                    "exchanges", 2, "exchangeFailures", 1, "cacheHits", 1, "physicalBusinessRequests", 2));
        }
    }

    @Test
    void queueSamplingUsesDatabaseDueTimeAndPreservesExplicitlyStaleValuesOnFailure() throws Exception {
        try (var fixture = new WorkerFixture(defaults())) {
            var processing = fixture.submit("sample-processing", "{}");
            assertEquals(processing, fixture.repository.claim("cp04-provider", "sample-held-lease", Duration.ofSeconds(30)).orElseThrow().taskId());
            var due = fixture.submit("sample-due", "{}");
            var future = fixture.submit("sample-future", "{}");
            fixture.api.sql("UPDATE notification_task SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id='" + future + "'");
            fixture.api.sql("UPDATE notification_task SET next_attempt_at=clock_timestamp()-interval '5 seconds' WHERE id='" + due + "'");
            var sampler = fixture.api.application.getBean(TaskQueueMetrics.class); assertTrue(sampler.refresh());
            assertEquals(2, meter(fixture.api, "notification.tasks", "VALUE", "status", "pending"));
            assertEquals(1, meter(fixture.api, "notification.tasks", "VALUE", "status", "processing"));
            double age = meter(fixture.api, "notification.queue.oldest_due.seconds", "VALUE");
            assertTrue(age >= 5 && age < 30, "Only due nextAttemptAt values may determine executable queue age");
            fixture.api.sql("UPDATE notification_task SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id='" + due + "'");
            assertTrue(sampler.refresh());
            assertEquals(0, meter(fixture.api, "notification.queue.oldest_due.seconds", "VALUE"));
            double beforeFailureAge = meter(fixture.api, "notification.queue.sample.age.seconds", "VALUE");
            fixture.api.sql("ALTER TABLE notification_task RENAME TO cp10_unavailable_task");
            try {
                assertFalse(sampler.refresh());
                assertEquals(0, meter(fixture.api, "notification.queue.sample.success", "VALUE"));
                assertEquals(1, meter(fixture.api, "notification.queue.sample.failures", "COUNT"));
                assertEquals(2, meter(fixture.api, "notification.tasks", "VALUE", "status", "pending"), "Failure must not fabricate an empty queue");
                assertTrue(meter(fixture.api, "notification.queue.sample.age.seconds", "VALUE") >= beforeFailureAge);
            } finally { fixture.api.sql("ALTER TABLE cp10_unavailable_task RENAME TO notification_task"); }
            assertTrue(sampler.refresh()); assertEquals(1, meter(fixture.api, "notification.queue.sample.success", "VALUE"));
            for (UUID id : List.of(processing, due, future)) fixture.evidence("cp10-queue-sampling", id, null);
            summary("cp10-queue-sampling", Map.of("acceptedTaskIds", List.of(processing, due, future).stream().map(UUID::toString).toList(),
                    "pending", 2, "processing", 1, "oldestDueSecondsWithFutureExcluded", age, "onlyFutureDueSeconds", 0,
                    "sampleFailures", 1, "staleValuesRetained", true));
        }
    }

    @Test
    void hikariWaitingAcquisitionTimeoutAndRecoveryAreObservableWithoutGaugeDatabaseQueries() throws Exception {
        // Two connections also allow the real Flyway migration's lock and migration sessions at startup.
        var properties = defaults(); properties.put("spring.datasource.hikari.maximum-pool-size", "2");
        properties.put("spring.datasource.hikari.connection-timeout", "2000");
        try (var api = new NotificationApiFixture(new RealPostgres(), "api", properties)) {
            assertTrue(api.application.getBean(TaskQueueMetrics.class).refresh());
            var pool = (HikariDataSource) api.application.getBean(DataSource.class);
            var registry = api.application.getBean(MeterRegistry.class);
            var jdbc = api.application.getBean(JdbcTemplate.class);
            var executor = Executors.newSingleThreadExecutor();
            try {
                double acquiredBefore = meter(api, "hikaricp.connections.acquire", "COUNT");
                var held = pool.getConnection(); var heldSecond = pool.getConnection();
                try {
                    var waiting = executor.submit(() -> jdbc.queryForObject("SELECT 1", Integer.class));
                    await(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);
                    assertEquals(1, meter(api, "hikaricp.connections.pending", "VALUE"));
                    assertEquals(2, meter(api, "hikaricp.connections.active", "VALUE"));
                    assertEquals(2, meter(api, "hikaricp.connections.max", "VALUE"));
                    assertEquals(0, meter(api, "notification.tasks", "VALUE", "status", "pending"), "Cached gauges must remain readable with no free DB connections");
                    held.close();
                    assertEquals(1, waiting.get(3, TimeUnit.SECONDS));
                } finally { held.close(); heldSecond.close(); }
                // Hikari's real PoolStats gauges refresh on a short cache interval after physical release.
                await(() -> registry.get("hikaricp.connections.pending").gauge().value() == 0);
                assertEquals(0, meter(api, "hikaricp.connections.pending", "VALUE"));
                assertTrue(meter(api, "hikaricp.connections.acquire", "COUNT") >= acquiredBefore + 3);
                double timeoutBefore = count(registry, "hikaricp.connections.timeout");
                try (var heldAgain = pool.getConnection(); var heldSecondAgain = pool.getConnection()) {
                    var waiting = executor.submit(() -> jdbc.queryForObject("SELECT 1", Integer.class));
                    await(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(4, TimeUnit.SECONDS));
                    assertInstanceOf(org.springframework.dao.DataAccessException.class, failure.getCause());
                    assertEquals(timeoutBefore + 1, meter(api, "hikaricp.connections.timeout", "COUNT"));
                    await(() -> registry.get("hikaricp.connections.pending").gauge().value() == 0);
                    assertEquals(0, meter(api, "hikaricp.connections.pending", "VALUE"));
                }
                assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
                await(() -> registry.get("hikaricp.connections.active").gauge().value() == 0);
                assertEquals(0, meter(api, "hikaricp.connections.active", "VALUE"));
                summary("cp10-hikari-metrics", Map.of("configuredMaximum", 2, "observedPending", 1, "timeouts", 1,
                        "pendingAfterRelease", 0, "cachedMetricsAvailableDuringExhaustion", true));
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    @Test
    void realTokenAndBusinessConnectionsExposeTheirOwnBoundedExecutorsAndReleaseToZero() throws Exception {
        var tokenArrived = new CountDownLatch(1); var releaseToken = new CountDownLatch(1);
        var businessArrived = new CountDownLatch(1); var releaseBusiness = new CountDownLatch(1);
        var properties = defaults(); properties.put("app.oauth.token-timeout", "5s");
        properties.put("app.worker.business-timeout", "5s"); properties.put("app.worker.attempt-timeout", "12s");
        properties.put("app.worker.lease-duration", "20s"); properties.put("app.oauth.http-concurrency", "1");
        try (var oauth = new OAuthFixture(properties, "client_secret_post")) {
            oauth.tokens.route("/token", e -> {
                tokenArrived.countDown();
                try { if (!releaseToken.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Token barrier expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted test barrier"); }
                HttpMock.respond(e, 200, OAuthFixture.response("cp10-resource-token", 3600));
            });
            oauth.worker.mock.route("/business", e -> {
                businessArrived.countDown();
                try { if (!releaseBusiness.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Business barrier expired"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted test barrier"); }
                HttpMock.respond(e, 200, "ok");
            });
            var id = oauth.submit("metric-resource-phases"); assertEquals(1, oauth.worker.worker.tick());
            assertTrue(tokenArrived.await(3, TimeUnit.SECONDS));
            assertEquals(1, meter(oauth.worker.api, "notification.oauth.inflight", "VALUE"));
            assertEquals(1, meter(oauth.worker.api, "notification.oauth.leaders.active", "VALUE"));
            assertEquals(1, meter(oauth.worker.api, "notification.http.connections.leased", "VALUE", "client", "token"));
            assertEquals(1, meter(oauth.worker.api, "notification.http.execution.active", "VALUE", "client", "token"));
            assertEquals(0, meter(oauth.worker.api, "notification.http.execution.active", "VALUE", "client", "business"));
            releaseToken.countDown(); assertTrue(businessArrived.await(3, TimeUnit.SECONDS));
            assertEquals(0, meter(oauth.worker.api, "notification.http.connections.leased", "VALUE", "client", "token"));
            assertEquals(1, meter(oauth.worker.api, "notification.http.connections.leased", "VALUE", "client", "business"));
            assertEquals(1, meter(oauth.worker.api, "notification.worker.active", "VALUE"));
            assertEquals(1, meter(oauth.worker.api, "notification.worker.provider.active.max", "VALUE"));
            assertEquals(0, meter(oauth.worker.api, "notification.worker.queued", "VALUE"));
            releaseBusiness.countDown(); oauth.worker.awaitStatus(id, "succeeded", Duration.ofSeconds(5)); oauth.worker.awaitIdle(Duration.ofSeconds(5));
            for (String client : List.of("business", "token")) {
                for (String metric : List.of("connections.leased", "connections.pending", "execution.active", "execution.queued", "execution.permits_used")) {
                    assertEquals(0, meter(oauth.worker.api, "notification.http." + metric, "VALUE", "client", client), metric);
                }
                assertEquals(1, meter(oauth.worker.api, "notification.http.connections.max", "VALUE", "client", client));
                assertEquals(1, meter(oauth.worker.api, "notification.http.execution.capacity", "VALUE", "client", client));
            }
            assertEquals(0, meter(oauth.worker.api, "notification.oauth.inflight", "VALUE"));
            assertEquals(0, meter(oauth.worker.api, "notification.worker.provider.active.max", "VALUE"));
            oauth.evidence("cp10-live-resource-metrics", id);
            summary("cp10-live-resource-metrics", Map.of("acceptedTaskIds", List.of(id.toString()), "tokenLeasedAtBarrier", 1,
                    "businessLeasedAtBarrier", 1, "perClientMaximum", 1, "remainingLeasesAndPermits", 0));
        } finally { releaseToken.countDown(); releaseBusiness.countDown(); }
    }

    @Test
    void backlogRejectionIsSeparateFromCommittedAdmissionAndIdempotentResolution() throws Exception {
        var properties = defaults(); properties.put("app.admission.max-backlog", "1");
        try (var fixture = new WorkerFixture(properties)) {
            fixture.mock.route("/notify", e -> HttpMock.respond(e, 200, "ok"));
            var first = fixture.submit("metric-backlog-first", "{}");
            String later = payload("tenant-a", "worker.notify", "metric-backlog-second", "{}");
            assertEquals(503, fixture.api.post(later, ADMIN).statusCode());
            assertEquals(200, fixture.api.post(payload("tenant-a", "worker.notify", "metric-backlog-first", "{}"), ADMIN).statusCode());
            assertEquals(1, meter(fixture.api, "notification.submission", "COUNT", "result", "ACCEPTED"));
            assertEquals(1, meter(fixture.api, "notification.submission", "COUNT", "result", "DEDUPLICATED"));
            assertEquals(1, meter(fixture.api, "notification.admission", "COUNT", "result", "BACKLOG_REJECTED"));
            assertEquals("succeeded", executeNextAttempt(fixture, first).path("status").asText());
            var acceptedLater = fixture.api.post(later, ADMIN); assertEquals(202, acceptedLater.statusCode());
            var second = UUID.fromString(fixture.api.json(acceptedLater).path("notificationId").asText());
            assertEquals("succeeded", executeNextAttempt(fixture, second).path("status").asText());
            assertEquals(2, meter(fixture.api, "notification.submission", "COUNT", "result", "ACCEPTED"));
            assertEquals(2, fixture.api.taskCount());
            summary("cp10-backlog-metrics", Map.of("acceptedTaskIds", List.of(first.toString(), second.toString()),
                    "committed", 2, "deduplicated", 1, "backlogRejected", 1, "retainedRows", 2));
        }
    }

    private static double count(MeterRegistry registry, String name, String... tags) {
        var meter = registry.find(name).tags(tags).counter(); return meter == null ? 0 : meter.count();
    }
    private static double meter(NotificationApiFixture api, String name, String statistic, String... tags) throws Exception {
        var path = new StringBuilder("/actuator/metrics/").append(name);
        for (int index = 0; index < tags.length; index += 2) path.append(index == 0 ? "?tag=" : "&tag=")
                .append(URLEncoder.encode(tags[index] + ":" + tags[index + 1], StandardCharsets.UTF_8));
        var response = api.get(path.toString(), ADMIN); assertEquals(200, response.statusCode(), path + ": " + response.body());
        for (JsonNode measurement : api.json(response).path("measurements")) {
            if (statistic.equals(measurement.path("statistic").asText())) return measurement.path("value").asDouble();
        }
        throw new AssertionError("Missing metric statistic " + name + " " + statistic);
    }
    private static void await(BooleanSupplier observation) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!observation.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(observation.getAsBoolean(), "Expected metric/resource state was not observed within two seconds");
    }
    private static void summary(String scenario, Map<String, ?> values) throws Exception {
        Path directory = Path.of("target", "evidence"); Files.createDirectories(directory);
        var document = JSON.createObjectNode().put("scenario", scenario).put("observedAt", Instant.now().toString());
        document.set("observations", JSON.valueToTree(values));
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario + "-summary.json").toFile(), document);
    }
}
