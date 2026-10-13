package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.auth.TokenCache;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import com.rc.notification.support.WorkerFaults;
import com.rc.notification.support.WorkerFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CP06 TC-AUTH-05/07/09..13: real time, durable recovery windows and authentication-version isolation. */
@Timeout(60)
class WorkerOAuthBoundaryTest {
    @Test
    void tokenLosingItsSendWindowAfterDurableIntentIsNotSentOrRefreshedInTheSameAttempt() throws Exception {
        var properties = WorkerOAuthTest.properties();
        properties.put("app.worker.attempt-timeout", "3s"); properties.put("app.worker.lease-duration", "5s");
        try (var o = new OAuthFixture(properties, "client_secret_basic", WorkerFaults.Configuration.class)) {
            var faults = o.worker.api.application.getBean(WorkerFaults.class);
            faults.arm(WorkerLifecycleListener.Phase.AFTER_REQUEST_STARTED, true);
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("short-send-window", 4)));
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var id = o.submit("token-late-before-send");
            assertEquals(1, o.worker.worker.tick()); assertTrue(faults.awaitReached());
            Thread.sleep(1250); // Initially 4s > 1s business + 2s safety; now less than that actual send window.
            faults.release(); o.worker.awaitIdle(Duration.ofSeconds(3));
            var pending = o.worker.task(id);
            assertEquals("pending", pending.path("status").asText());
            assertEquals("NOT_SENT", pending.path("last_outcome").asText());
            assertEquals("TOKEN_EXPIRED_BEFORE_SEND", pending.path("last_error_code").asText());
            assertEquals(1, pending.path("run_attempt_count").asInt());
            assertEquals(1, o.tokens.journal().size()); assertEquals(0, o.worker.mock.journal().size());
            assertFalse(o.worker.attempts(id).get(0).path("request_started_at").isNull());
            assertTrue(o.worker.attempts(id).get(0).path("http_send_started_at").isNull());
            o.evidence("cp06-token-expired-before-send", id);
            assertEquals("succeeded", executeNextAttempt(o.worker, id).path("status").asText());
            assertEquals(2, o.tokens.journal().size()); assertEquals(1, o.worker.mock.journal().size());
        }
    }

    @ParameterizedTest @CsvSource({"503,TOKEN_SERVER_ERROR", "429,TOKEN_RATE_LIMITED", "408,TOKEN_TIMEOUT"})
    void temporaryTokenHttpFailuresConsumeOneAttemptAndRecoverWithoutSendingUnauthenticatedBusiness(int status, String code) throws Exception {
        var requests = new AtomicInteger();
        try (var o = new OAuthFixture(WorkerOAuthTest.properties(), "client_secret_post")) {
            o.tokens.route("/token", e -> {
                if (requests.incrementAndGet() == 1) {
                    if (status == 429) e.getResponseHeaders().set("Retry-After", "1");
                    HttpMock.respond(e, status, "temporary");
                } else HttpMock.respond(e, 200, OAuthFixture.response("temporary-recovery", 3600));
            });
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var id = o.submit("token-http-failure-" + status);
            var pending = executeNextAttempt(o.worker, id);
            assertEquals("pending", pending.path("status").asText()); assertEquals("NOT_SENT", pending.path("last_outcome").asText());
            assertEquals(code, pending.path("last_error_code").asText()); assertEquals(1, pending.path("run_attempt_count").asInt());
            assertEquals(0, o.worker.mock.journal().size()); assertEquals(1, o.tokens.journal().size());
            if (status == 429) assertFalse(Instant.parse(pending.path("next_attempt_at").asText())
                    .isBefore(o.tokens.journal().get(0).receivedAt().plusSeconds(1)));
            o.evidence("cp06-token-http-" + status + "-pending", id);
            assertEquals("succeeded", executeNextAttempt(o.worker, id).path("status").asText());
            assertEquals(2, o.tokens.journal().size()); assertEquals(1, o.worker.mock.journal().size());
        }
    }

    @Test
    void actualTokenTimeoutFinishesNotSentWithinAttemptAndCanBeRetried() throws Exception {
        var properties = WorkerOAuthTest.properties(); properties.put("app.oauth.token-timeout", "150ms");
        var requests = new AtomicInteger();
        try (var o = new OAuthFixture(properties, "client_secret_post")) {
            o.tokens.route("/token", e -> {
                if (requests.incrementAndGet() == 1) {
                    try { Thread.sleep(400); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                HttpMock.respond(e, 200, OAuthFixture.response("timeout-recovery", 3600));
            });
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var id = o.submit("token-real-timeout");
            var pending = executeNextAttempt(o.worker, id);
            assertEquals("pending", pending.path("status").asText()); assertEquals("NOT_SENT", pending.path("last_outcome").asText());
            assertEquals("TOKEN_TIMEOUT", pending.path("last_error_code").asText());
            assertEquals(1, pending.path("run_attempt_count").asInt()); assertEquals(0, o.worker.mock.journal().size());
            var attempt = o.worker.attempts(id).get(0);
            assertFalse(Instant.parse(attempt.path("ended_at").asText()).isAfter(Instant.parse(attempt.path("execution_deadline").asText())));
            o.evidence("cp06-token-real-timeout", id);
            assertEquals("succeeded", executeNextAttempt(o.worker, id).path("status").asText());
            assertEquals(1, o.worker.mock.journal().size()); assertEquals(2, o.tokens.journal().size());
        }
    }

    @Test
    void failurePersistsCooldownAndAnotherTaskDefersBeforeReservingAnAttempt() throws Exception {
        var properties = WorkerOAuthTest.properties();
        properties.put("app.oauth.cooldown-min", "1s"); properties.put("app.oauth.cooldown-max", "1s");
        var requests = new AtomicInteger();
        try (var o = new OAuthFixture(properties, "client_secret_basic")) {
            o.tokens.route("/token", e -> {
                if (requests.incrementAndGet() == 1) HttpMock.respond(e, 503, "temporary");
                else HttpMock.respond(e, 200, OAuthFixture.response("cooldown-recovered", 3600));
            });
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var first = o.submit("cooldown-first");
            var failedAttempt = executeNextAttempt(o.worker, first);
            assertEquals("pending", failedAttempt.path("status").asText()); assertEquals("NOT_SENT", failedAttempt.path("last_outcome").asText());
            assertFalse(Instant.parse(failedAttempt.path("next_attempt_at").asText())
                    .isBefore(o.tokens.journal().get(0).receivedAt().plusSeconds(1)), "T4 must persist the authentication cooldown immediately");
            var second = o.submit("cooldown-second");
            var beforeDeferral = o.worker.task(second);
            assertEquals(1, o.worker.worker.tick()); o.worker.awaitIdle(Duration.ofSeconds(2));
            var deferred = o.worker.task(second);
            assertEquals("pending", deferred.path("status").asText()); assertEquals("AUTH_COOLDOWN", deferred.path("defer_reason").asText());
            assertEquals(0, deferred.path("run_attempt_count").asInt()); assertEquals(0, o.worker.attempts(second).size());
            assertEquals("NOT_SENT", beforeDeferral.path("last_outcome").asText());
            assertEquals(beforeDeferral.path("last_outcome"), deferred.path("last_outcome"));
            assertEquals(beforeDeferral.path("last_error_code"), deferred.path("last_error_code"));
            assertEquals(beforeDeferral.path("last_http_status"), deferred.path("last_http_status"));
            assertEquals(1, o.tokens.journal().size()); assertEquals(0, o.worker.mock.journal().size());
            o.evidence("cp06-auth-cooldown-first", first); o.evidence("cp06-auth-cooldown-unreserved", second);
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (System.nanoTime() < deadline && (!o.worker.task(first).path("status").asText().equals("succeeded")
                    || !o.worker.task(second).path("status").asText().equals("succeeded"))) {
                o.worker.worker.tick(); o.worker.awaitIdle(Duration.ofSeconds(2)); Thread.sleep(10);
            }
            assertEquals("succeeded", o.worker.task(first).path("status").asText()); assertEquals("succeeded", o.worker.task(second).path("status").asText());
            assertEquals(2, o.worker.task(first).path("run_attempt_count").asInt()); assertEquals(1, o.worker.task(second).path("run_attempt_count").asInt());
            assertEquals(2, o.tokens.journal().size()); assertEquals(2, o.worker.mock.journal().size());
        }
    }

    @Test
    void lateSharedTokenCompletionCannotSendAfterItsWaitingAttemptHasRunOutOfTime() throws Exception {
        var properties = WorkerOAuthTest.properties();
        properties.put("app.worker.business-timeout", "100ms"); properties.put("app.worker.attempt-timeout", "250ms");
        properties.put("app.worker.lease-duration", "800ms"); properties.put("app.oauth.token-timeout", "2s");
        var arrived = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var o = new OAuthFixture(properties, "client_secret_basic")) {
            o.tokens.route("/token", e -> {
                arrived.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                HttpMock.respond(e, 200, OAuthFixture.response("late-shared-token", 3600));
            });
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var id = o.submit("token-exhausted-attempt"); assertEquals(1, o.worker.worker.tick()); assertTrue(arrived.await(1, TimeUnit.SECONDS));
            o.worker.awaitIdle(Duration.ofSeconds(1));
            var state = o.worker.task(id); var attempt = o.worker.attempts(id).get(0);
            assertEquals(1, state.path("run_attempt_count").asInt()); assertEquals(0, o.worker.mock.journal().size());
            if (attempt.path("ended_at").isNull()) {
                assertEquals("processing", state.path("status").asText());
                try (var connection = o.worker.api.database.connection(); var statement = connection.createStatement(); var row = statement.executeQuery(
                        "SELECT execution_deadline<=clock_timestamp() FROM " + o.worker.api.database.schema() + ".notification_attempt WHERE task_id='" + id + "'")) {
                    assertTrue(row.next()); assertTrue(row.getBoolean(1));
                }
            } else {
                assertEquals("pending", state.path("status").asText()); assertEquals("NOT_SENT", attempt.path("outcome").asText());
                assertEquals("TOKEN_TIMEOUT", attempt.path("error_code").asText());
                assertFalse(Instant.parse(attempt.path("ended_at").asText()).isAfter(Instant.parse(attempt.path("execution_deadline").asText())));
            }
            release.countDown();
            var cache = o.worker.api.application.getBean(TokenCache.class);
            long cacheDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (cache.inFlightCount() != 0 && System.nanoTime() < cacheDeadline) Thread.sleep(5);
            assertEquals(0, cache.inFlightCount()); assertEquals(0, o.worker.mock.journal().size());
            assertEquals(1, o.worker.attempts(id).size(), "Late token leader completion only populates the cache");
            if (state.path("status").asText().equals("processing")) {
                o.worker.awaitExpiredLease(id, Duration.ofSeconds(2)); assertEquals(1, o.worker.repository.recoverExpired(10));
                assertEquals("UNKNOWN", o.worker.attempts(id).get(0).path("outcome").asText());
            }
            o.evidence("cp06-token-late-leader-no-send", id);
            assertEquals("succeeded", executeNextAttempt(o.worker, id).path("status").asText());
            assertEquals(2, o.worker.attempts(id).size()); assertEquals(1, o.worker.mock.journal().size()); assertEquals(1, o.tokens.journal().size());
        } finally { release.countDown(); }
    }

    @Test
    void crashAfterRecoveryCommitBeforeCacheInvalidationStillForcesADifferentToken() throws Exception {
        var requests = new AtomicInteger();
        try (var o = new OAuthFixture(WorkerOAuthTest.properties(), "client_secret_basic", WorkerFaults.Configuration.class)) {
            var faults = o.worker.api.application.getBean(WorkerFaults.class);
            faults.arm(WorkerLifecycleListener.Phase.AFTER_COMPLETION, false);
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("commit-window-" + requests.incrementAndGet(), 3600)));
            o.worker.mock.route("/business", e -> {
                if (e.getRequestHeaders().getFirst("Authorization").endsWith("-1")) {
                    e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired");
                } else HttpMock.respond(e, 200, "ok");
            });
            var id = o.submit("auth-recovery-commit-window"); var pending = executeNextAttempt(o.worker, id);
            assertTrue(faults.awaitReached()); assertEquals("pending", pending.path("status").asText());
            assertTrue(pending.path("auth_recovery_required").asBoolean()); assertTrue(pending.path("auth_recovery_used").asBoolean());
            assertEquals(1, o.tokens.journal().size()); assertEquals(1, o.worker.mock.journal().size());
            o.evidence("cp06-auth-recovery-commit-window", id);
            assertEquals("succeeded", executeNextAttempt(o.worker, id).path("status").asText());
            assertEquals(2, o.tokens.journal().size()); assertEquals(2, o.worker.mock.journal().size());
        }
    }

    @Test
    void anApiKeyRequestDoesNotAcquireOAuthRecoveryFromAnInvalidTokenChallenge() throws Exception {
        try (var f = new WorkerFixture(fastRetries())) {
            f.mock.route("/notify", e -> { e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired"); });
            var id = f.submit("api-key-invalid-token-challenge", "{}"); var state = executeNextAttempt(f, id);
            assertEquals("failed", state.path("status").asText()); assertFalse(state.path("auth_recovery_used").asBoolean());
            assertEquals(1, state.path("run_attempt_count").asInt()); assertEquals(1, f.mock.journal().size()); assertEquals(0, f.worker.tick());
        }
    }

    @Test
    void aNewlyIssuedTokenTooShortForTheRequestFailsWithoutARefreshLoop() throws Exception {
        try (var o = new OAuthFixture(WorkerOAuthTest.properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("too-short", 2)));
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "must-not-arrive"));
            var id = o.submit("token-too-short"); var state = executeNextAttempt(o.worker, id);
            assertEquals("failed", state.path("status").asText()); assertEquals("NOT_SENT", state.path("last_outcome").asText());
            assertEquals("TOKEN_LIFETIME_INCOMPATIBLE", state.path("last_error_code").asText()); assertEquals(1, state.path("run_attempt_count").asInt());
            assertEquals(1, o.tokens.journal().size()); assertEquals(0, o.worker.mock.journal().size()); assertEquals(0, o.worker.worker.tick());
        }
    }

    @Test
    void aNewAccountVersionChangesNewTasksOnlyAndKeepsOldAuthorizationParametersFrozen() throws Exception {
        assertEquals("cp06 rotated secret", System.getenv("CP06_ROTATED_SECRET"));
        assertEquals("fixture-oauth-v2", System.getenv("CP06_ROTATED_SECRET_VERSION"));
        try (var o = new OAuthFixture(WorkerOAuthTest.properties(), "client_secret_post")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("original-account-token", 3600)));
            o.tokens.route("/new-token", e -> HttpMock.respond(e, 200, OAuthFixture.response("new-account-token", 3600)));
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var oldTask = o.submit("queued-old-account");
            var replacement = com.rc.notification.support.ConfigurationFixture.oauth("tenant-a", o.tokens.uri("/new-token").toString())
                    .put("authProfileKey", o.auth.path("authProfileKey").asText()).put("version", 2).put("clientId", "new-account")
                    .put("clientSecretRef", "env:CP06_ROTATED_SECRET").put("clientAuthenticationMethod", "client_secret_post");
            replacement.putArray("scopes").add("new-scope");
            var published = o.worker.configuration.publish("auth-profiles", o.worker.configuration.createAuth(replacement));
            o.worker.configuration.bind("tenant-a", "oauth.notify", o.template, published, 1);
            var newTask = o.submit("new-account-task");
            assertEquals("succeeded", executeNextAttempt(o.worker, oldTask).path("status").asText());
            assertEquals("succeeded", executeNextAttempt(o.worker, newTask).path("status").asText());
            assertEquals(o.auth.path("id").asText(), o.worker.task(oldTask).path("auth_profile_id").asText());
            assertEquals(published.path("id").asText(), o.worker.task(newTask).path("auth_profile_id").asText());
            assertEquals("fixture-oauth-v1", o.worker.attempts(oldTask).get(0).path("credential_version").asText());
            assertEquals("fixture-oauth-v2", o.worker.attempts(newTask).get(0).path("credential_version").asText());
            assertEquals(2, o.tokens.journal().size());
            var oldRequest = o.tokens.journal().stream().filter(r -> r.uri().getPath().equals("/token")).findFirst().orElseThrow();
            var newRequest = o.tokens.journal().stream().filter(r -> r.uri().getPath().equals("/new-token")).findFirst().orElseThrow();
            assertTrue(new String(oldRequest.body(), StandardCharsets.UTF_8).contains("client_id=client%3A+%2B%E4%B8%AD"));
            assertTrue(new String(oldRequest.body(), StandardCharsets.UTF_8).contains("scope=read+write"));
            assertTrue(new String(newRequest.body(), StandardCharsets.UTF_8).contains("client_secret=cp06+rotated+secret"));
            assertTrue(new String(newRequest.body(), StandardCharsets.UTF_8).contains("scope=new-scope"));
            assertEquals("Bearer original-account-token", o.worker.mock.journal().get(0).firstHeader("Authorization"));
            assertEquals("Bearer new-account-token", o.worker.mock.journal().get(1).firstHeader("Authorization"));
            o.evidence("cp06-fixed-old-auth-version", oldTask); o.evidence("cp06-new-auth-version", newTask);
        }
    }
}
