package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.ConfigurationFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.protection.ProtectionGate;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import com.rc.notification.support.WorkerFaults;
import com.rc.notification.support.WorkerFixture;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CP07 TC-GUARD-01..04/06/07 and AT27: protection wired into real workers, PostgreSQL and HTTP. */
@Timeout(60)
class WorkerProtectionTest {
    static Map<String, Object> properties() {
        var p = new java.util.HashMap<String, Object>(fastRetries());
        p.put("app.protection.jitter", "0s"); p.put("app.protection.retry-delay", "20ms");
        p.put("app.protection.quota-cooldown", "1ms"); return p;
    }
    static Lease key(WorkerFixture f, UUID id) throws Exception {
        var t = f.task(id);
        return new Lease(id, t.path("run_number").asInt(), UUID.randomUUID(), "test-observer", Instant.now().plusSeconds(60),
                t.path("tenant_id").asText(), t.path("operation").asText(), t.path("event_id").asText(), t.path("provider_id").asText(),
                t.path("endpoint_group").asText(), UUID.fromString(t.path("auth_profile_id").asText()),
                new RequestSnapshot("https://supplier.invalid/notify", "POST", Map.of(), "application/json", new byte[0]));
    }
    static void primeOpen(ProtectionGate gate, Lease key) {
        for (int n = 0; n < 10; n++) {
            var admission = gate.acquire(key); assertTrue(admission.admitted()); admission.permit().complete(503, null, Duration.ZERO);
        }
        assertEquals("OPEN", gate.circuit(key).orElseThrow().state());
    }

    @ParameterizedTest @ValueSource(ints = {200, 503})
    void realClaimedTasksCompeteForExactlyOneHalfOpenHttpProbeWithoutChargingTheOthers(int status) throws Exception {
        var p = properties(); p.put("app.worker.global-concurrency", "3"); p.put("app.worker.provider-concurrency", "3");
        p.put("app.worker.business-timeout", "3s"); p.put("app.worker.attempt-timeout", "4s"); p.put("app.worker.lease-duration", "6s");
        p.put("app.delivery.max-attempts", "1"); p.put("app.protection.open-duration", "100ms"); p.put("app.worker.shutdown-grace-period", "0s");
        var arrived = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var f = new WorkerFixture(p)) {
            f.mock.route("/notify", e -> {
                arrived.countDown();
                try { assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                HttpMock.respond(e, status, "probe-result");
            });
            var ids = new java.util.LinkedHashMap<String, UUID>();
            for (int n = 0; n < 3; n++) {
                String event = "real-probe-" + status + "-" + n; ids.put(event, f.submit(event, "{}"));
            }
            var gate = f.api.application.getBean(ProtectionGate.class); var domain = key(f, ids.values().iterator().next());
            primeOpen(gate, domain); Thread.sleep(130);
            assertEquals(3, f.worker.tick()); assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, f.mock.journal().size());
            UUID probe = ids.get(f.mock.journal().get(0).firstHeader("X-Event-Id")); assertNotNull(probe);
            for (UUID id : ids.values()) {
                if (id.equals(probe)) { assertEquals(1, f.task(id).path("run_attempt_count").asInt()); continue; }
                var denied = f.awaitStatus(id, "pending", Duration.ofSeconds(2));
                assertEquals("CIRCUIT_HALF_OPEN", denied.path("defer_reason").asText()); assertEquals(0, denied.path("run_attempt_count").asInt());
                assertEquals(0, f.attempts(id).size());
            }
            assertEquals("HALF_OPEN", gate.circuit(domain).orElseThrow().state()); assertEquals(1, gate.circuit(domain).orElseThrow().activePermits());
            release.countDown(); f.awaitIdle(Duration.ofSeconds(2));
            assertEquals(status == 200 ? "succeeded" : "failed", f.task(probe).path("status").asText());
            assertEquals(status == 200 ? "CLOSED" : "OPEN", gate.circuit(domain).orElseThrow().state());
            assertEquals(1, f.mock.journal().size()); assertEquals(0, gate.circuit(domain).orElseThrow().activePermits());
            for (UUID id : ids.values()) {
                if (!id.equals(probe)) assertEquals(0, f.task(id).path("run_attempt_count").asInt());
                f.evidence("cp07-half-open-real-" + status + (id.equals(probe) ? "-probe" : "-unreserved"), id, null);
            }
        } finally { release.countDown(); }
    }

    @Test
    void actualNineAndTenSamplesOpenTheCircuitAndUnsentTasksStillReachTheirDeadline() throws Exception {
        var p = properties(); p.put("app.delivery.max-attempts", "1");
        try (var f = new WorkerFixture(p)) {
            var calls = new AtomicInteger();
            f.mock.route("/notify", e -> HttpMock.respond(e, calls.incrementAndGet() <= 5 ? 503 : 200, "ignored"));
            var gate = f.api.application.getBean(ProtectionGate.class); UUID latest = null;
            for (int n = 1; n <= 9; n++) { latest = f.submit("circuit-prime-" + n, "{}"); executeNextAttempt(f, latest); }
            var domain = key(f, latest); var nine = gate.circuit(domain).orElseThrow();
            assertEquals("CLOSED", nine.state()); assertEquals(9, nine.bufferedCalls()); assertEquals(5, nine.failedCalls());
            latest = f.submit("circuit-prime-10", "{}"); executeNextAttempt(f, latest);
            assertEquals("OPEN", gate.circuit(domain).orElseThrow().state()); assertEquals(10, f.mock.journal().size());
            var waiting = f.submit("circuit-open-unreserved", "{}");
            var deferred = executeNextAttempt(f, waiting);
            assertEquals("pending", deferred.path("status").asText()); assertEquals("CIRCUIT_OPEN", deferred.path("defer_reason").asText());
            assertEquals(0, deferred.path("run_attempt_count").asInt()); assertEquals(0, f.attempts(waiting).size());
            for (int n = 0; n < 3; n++) { assertEquals(0, f.worker.tick()); Thread.sleep(30); }
            assertEquals(10, f.mock.journal().size());
            f.evidence("cp07-open-no-attempt", waiting, null);
            f.api.sql("UPDATE notification_task SET deadline_at=clock_timestamp()+interval '150 milliseconds' WHERE id='" + waiting + "'");
            Thread.sleep(200); f.worker.tick(); f.awaitIdle(Duration.ofSeconds(2));
            var expired = f.task(waiting); assertEquals("failed", expired.path("status").asText());
            assertEquals("DEADLINE_EXCEEDED", expired.path("defer_reason").asText()); assertEquals(0, expired.path("run_attempt_count").asInt());
            assertEquals(0, f.attempts(waiting).size()); assertEquals(10, f.mock.journal().size());
            f.evidence("cp07-open-independent-deadline", waiting, null);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"token-failure", "token-rate-limit", "token-timeout", "cancel-after-reserve", "cancel-after-intent"})
    void aHalfOpenProbeThatNeverSendsBusinessReleasesItsPermitForANewHealthyTask(String failure) throws Exception {
        var p = properties(); p.put("app.delivery.max-attempts", "1"); p.put("app.protection.open-duration", "100ms");
        p.put("app.oauth.cooldown-min", "1ms"); p.put("app.oauth.cooldown-max", "1ms");
        if (failure.equals("token-timeout")) p.put("app.oauth.token-timeout", "100ms");
        try (var o = new OAuthFixture(p, "client_secret_post", WorkerFaults.Configuration.class)) {
            var tokenCalls = new AtomicInteger();
            o.tokens.route("/token", e -> {
                int n = tokenCalls.incrementAndGet();
                if (n == 1 && failure.equals("token-failure")) HttpMock.respond(e, 503, "temporary");
                else if (n == 1 && failure.equals("token-rate-limit")) {
                    e.getResponseHeaders().set("Retry-After", "0"); HttpMock.respond(e, 429, "temporary-quota");
                }
                else {
                    if (n == 1 && failure.equals("token-timeout")) {
                        try { Thread.sleep(200); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    HttpMock.respond(e, 200, OAuthFixture.response("probe-token", 3600));
                }
            });
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var first = o.submit("probe-not-sent-" + failure); var gate = o.worker.api.application.getBean(ProtectionGate.class); var domain = key(o.worker, first);
            primeOpen(gate, domain); Thread.sleep(130);
            if (failure.startsWith("cancel")) o.worker.api.application.getBean(WorkerFaults.class).arm(
                    failure.equals("cancel-after-reserve") ? WorkerLifecycleListener.Phase.AFTER_RESERVE : WorkerLifecycleListener.Phase.AFTER_REQUEST_STARTED, false);
            executeNextAttempt(o.worker, first);
            var after = gate.circuit(domain).orElseThrow(); assertEquals("HALF_OPEN", after.state());
            assertEquals(0, after.activePermits()); assertEquals(0, after.bufferedCalls()); assertEquals(0, o.worker.mock.journal().size());
            assertEquals(1, o.worker.task(first).path("run_attempt_count").asInt());
            if (failure.startsWith("token")) assertEquals("NOT_SENT", o.worker.task(first).path("last_outcome").asText());
            o.evidence("cp07-half-open-" + failure, first);
            Thread.sleep(10); var second = o.submit("probe-healthy-" + failure);
            assertEquals("succeeded", executeNextAttempt(o.worker, second).path("status").asText());
            assertEquals(1, o.worker.mock.journal().size()); assertEquals("CLOSED", gate.circuit(domain).orElseThrow().state());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"seconds", "date"})
    void quotaCoolingDefersUnsentSameContextWithoutBlockingAnotherAuthenticationContext(String kind) throws Exception {
        var p = properties(); p.put("app.worker.global-concurrency", "2");
        try (var f = new WorkerFixture(p)) {
            var calls = new AtomicInteger(); var lower = new java.util.concurrent.atomic.AtomicReference<Instant>();
            f.mock.route("/notify", e -> {
                if (calls.incrementAndGet() == 1) {
                    if (kind.equals("seconds")) { lower.set(Instant.now().plusSeconds(1)); e.getResponseHeaders().set("Retry-After", "1"); }
                    else { lower.set(Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
                        e.getResponseHeaders().set("Retry-After", HttpMock.httpDate(lower.get())); }
                    HttpMock.respond(e, 429, "quota");
                } else HttpMock.respond(e, 200, "ok");
            });
            f.mock.route("/other-auth", e -> HttpMock.respond(e, 200, "ok"));
            var otherTemplate = scalarTemplate("tenant-a", "other-auth").put("providerId", "cp04-provider").put("url", f.mock.uri("/other-auth").toString());
            f.configuration.install(otherTemplate, apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET"), "other.auth");
            var first = f.submit("quota-trigger-" + kind, "{}"); var firstState = executeNextAttempt(f, first);
            assertEquals(1, firstState.path("run_attempt_count").asInt()); assertEquals("pending", firstState.path("status").asText());
            var gate = f.api.application.getBean(ProtectionGate.class); var domain = key(f, first);
            assertEquals(0, gate.circuit(domain).orElseThrow().bufferedCalls());
            var delayed = f.submit("quota-unsent-" + kind, "{}");
            var response = f.api.post(payload("tenant-a", "other.auth", "quota-other-" + kind, "{}"), ADMIN); assertEquals(202, response.statusCode());
            var other = UUID.fromString(f.api.json(response).path("notificationId").asText());
            assertEquals(2, f.worker.tick()); f.awaitIdle(Duration.ofSeconds(2));
            var held = f.task(delayed); assertEquals("QUOTA_COOLDOWN", held.path("defer_reason").asText());
            assertEquals(0, held.path("run_attempt_count").asInt()); assertEquals(0, f.attempts(delayed).size());
            assertEquals("succeeded", f.task(other).path("status").asText()); assertEquals(1, f.mock.count("/notify"));
            for (int n = 0; n < 3; n++) { f.worker.tick(); Thread.sleep(30); }
            assertEquals(1, f.mock.count("/notify"));
            f.evidence("cp07-quota-" + kind + "-unreserved", delayed, null);
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < until && (!f.task(first).path("status").asText().equals("succeeded") || !f.task(delayed).path("status").asText().equals("succeeded"))) {
                f.worker.tick(); f.awaitIdle(Duration.ofSeconds(2)); Thread.sleep(10);
            }
            assertEquals("succeeded", f.task(first).path("status").asText()); assertEquals("succeeded", f.task(delayed).path("status").asText());
            assertEquals(3, f.mock.count("/notify"));
            for (var call : f.mock.journal().stream().filter(r -> r.uri().getPath().equals("/notify")).skip(1).toList()) assertFalse(call.receivedAt().isBefore(lower.get()));
            assertEquals(1, f.task(delayed).path("run_attempt_count").asInt()); f.evidence("cp07-quota-" + kind + "-recovered", delayed, null);
        }
    }

    @Test
    void aNewQuotaRestrictionAfterT2KeepsItsReservedAttemptButPreventsTheFirstPhysicalSend() throws Exception {
        var p = properties(); p.put("app.worker.global-concurrency", "2");
        try (var f = new WorkerFixture(p, WorkerFaults.Configuration.class)) {
            f.mock.route("/notify", e -> { e.getResponseHeaders().set("Retry-After", "2"); HttpMock.respond(e, 429, "quota"); });
            var faults = f.api.application.getBean(WorkerFaults.class); faults.arm(WorkerLifecycleListener.Phase.AFTER_REQUEST_STARTED, true);
            var paused = f.submit("quota-already-reserved", "{}"); assertEquals(1, f.worker.tick()); assertTrue(faults.awaitReached());
            var trigger = f.submit("quota-arrives-during-pause", "{}"); assertEquals(1, f.worker.tick());
            f.awaitStatus(trigger, "pending", Duration.ofSeconds(2)); assertEquals(1, f.mock.journal().size());
            faults.release(); f.awaitIdle(Duration.ofSeconds(2));
            var state = f.task(paused); assertEquals("pending", state.path("status").asText());
            assertEquals(1, state.path("run_attempt_count").asInt()); assertEquals("NOT_SENT", state.path("last_outcome").asText());
            assertEquals("QUOTA_COOLDOWN", state.path("last_error_code").asText());
            assertEquals(1, f.mock.journal().size()); assertEquals("quota-arrives-during-pause", f.mock.journal().get(0).firstHeader("X-Event-Id"));
            assertTrue(f.attempts(paused).get(0).path("http_send_started_at").isNull());
            f.evidence("cp07-quota-after-reservation", paused, null);
        }
    }

    @Test
    void aQuotaRestrictionArrivingDuringActualOAuthExchangePreventsBusinessSendWithoutUndoingT2() throws Exception {
        var p = properties(); p.put("app.worker.global-concurrency", "2"); p.put("app.worker.business-timeout", "3s");
        p.put("app.worker.attempt-timeout", "5s"); p.put("app.worker.lease-duration", "8s"); p.put("app.worker.shutdown-grace-period", "0s");
        var businessArrived = new java.util.concurrent.CountDownLatch(1); var releaseBusiness = new java.util.concurrent.CountDownLatch(1);
        var tokenArrived = new java.util.concurrent.CountDownLatch(1); var releaseToken = new java.util.concurrent.CountDownLatch(1);
        try (var o = new OAuthFixture(p, "client_secret_basic")) {
            var tokenCalls = new AtomicInteger();
            o.tokens.route("/token", e -> {
                if (tokenCalls.incrementAndGet() == 1) HttpMock.respond(e, 200, OAuthFixture.response("before-quota-token", 6));
                else {
                    tokenArrived.countDown();
                    try { assertTrue(releaseToken.await(4, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    HttpMock.respond(e, 200, OAuthFixture.response("after-quota-token", 3600));
                }
            });
            o.worker.mock.route("/business", e -> {
                businessArrived.countDown();
                try { assertTrue(releaseBusiness.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                e.getResponseHeaders().set("Retry-After", "2"); HttpMock.respond(e, 429, "quota");
            });
            var inFlight = o.submit("quota-before-token-wait"); assertEquals(1, o.worker.worker.tick());
            assertTrue(businessArrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(1250); // 6s initial lifetime now cannot cover the next 3s request plus 2s margin.
            var waiting = o.submit("quota-during-token-wait"); assertEquals(1, o.worker.worker.tick());
            assertTrue(tokenArrived.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, o.worker.task(waiting).path("run_attempt_count").asInt());
            assertEquals(1, o.worker.mock.journal().size());
            releaseBusiness.countDown(); o.worker.awaitStatus(inFlight, "pending", Duration.ofSeconds(1));
            releaseToken.countDown(); o.worker.awaitIdle(Duration.ofSeconds(2));
            var state = o.worker.task(waiting); assertEquals("pending", state.path("status").asText());
            assertEquals("NOT_SENT", state.path("last_outcome").asText()); assertEquals("QUOTA_COOLDOWN", state.path("last_error_code").asText());
            assertEquals(1, state.path("run_attempt_count").asInt()); assertEquals(1, o.worker.mock.journal().size()); assertEquals(2, o.tokens.journal().size());
            assertEquals("quota-before-token-wait", o.worker.mock.journal().get(0).firstHeader("X-Event-Id"));
            assertTrue(o.worker.attempts(waiting).get(0).path("http_send_started_at").isNull());
            assertEquals(0, o.worker.api.application.getBean(ProtectionGate.class).circuit(key(o.worker, waiting)).orElseThrow().bufferedCalls());
            o.evidence("cp07-quota-during-token-wait", waiting);
        } finally { releaseBusiness.countDown(); releaseToken.countDown(); }
    }

    @Test
    void longQuotaCoolingCannotKeepANewTaskAliveBeyondItsOriginalDeadline() throws Exception {
        try (var f = new WorkerFixture(properties())) {
            f.mock.route("/notify", e -> { e.getResponseHeaders().set("Retry-After", "3600"); HttpMock.respond(e, 429, "quota"); });
            var trigger = f.submit("quota-long-trigger", "{}"); executeNextAttempt(f, trigger);
            var expires = f.submit("quota-short-deadline", "{}");
            f.api.sql("UPDATE notification_task SET deadline_at=clock_timestamp()+interval '250 milliseconds' WHERE id='" + expires + "'");
            var state = executeNextAttempt(f, expires);
            assertEquals("failed", state.path("status").asText()); assertEquals("DEADLINE_EXCEEDED", state.path("defer_reason").asText());
            assertEquals(0, state.path("run_attempt_count").asInt()); assertEquals(0, f.attempts(expires).size()); assertEquals(1, f.mock.journal().size());
            f.evidence("cp07-quota-deadline-unreserved", expires, null);
        }
    }
}
