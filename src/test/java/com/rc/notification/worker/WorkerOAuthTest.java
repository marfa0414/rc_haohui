package com.rc.notification.worker;

import static com.rc.notification.support.NotificationApiFixture.*;
import static com.rc.notification.support.RetryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.OAuthFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** CP06 TC-AUTH-02/03/07..10/12/13: actual environment secret, API config, worker, PostgreSQL and both HTTP endpoints. */
@Timeout(60)
class WorkerOAuthTest {
    static Map<String, Object> properties() {
        var properties = new java.util.HashMap<String, Object>(fastRetries());
        properties.put("app.oauth.cooldown-min", "1ms"); properties.put("app.oauth.cooldown-max", "1ms");
        return properties;
    }

    @ParameterizedTest @ValueSource(strings = {"client_secret_basic", "client_secret_post"})
    void environmentBackedOAuthExamplesSendBearerAndReuseTheContextAcrossOperations(String method) throws Exception {
        try (var o = new OAuthFixture(properties(), method)) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("environment-token", 3600)));
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "ok"));
            var first = o.submit("environment-auth-" + method);
            assertEquals("succeeded", executeNextAttempt(o.worker, first).path("status").asText());
            o.worker.configuration.bind("tenant-a", "oauth.other", o.template, o.auth, 0);
            var accepted = o.worker.api.post(payload("tenant-a", "oauth.other", "same-auth-other-operation", "{}"), ADMIN);
            assertEquals(202, accepted.statusCode());
            var second = UUID.fromString(o.worker.api.json(accepted).path("notificationId").asText());
            assertEquals("succeeded", executeNextAttempt(o.worker, second).path("status").asText());
            assertEquals(1, o.tokens.journal().size()); assertEquals(2, o.worker.mock.count("/business"));
            for (var request : o.worker.mock.journal()) assertEquals("Bearer environment-token", request.firstHeader("Authorization"));
            var tokenRequest = o.tokens.journal().get(0);
            String requestText = new String(tokenRequest.body(), java.nio.charset.StandardCharsets.UTF_8);
            if (method.equals("client_secret_post")) {
                assertTrue(requestText.contains("client_secret=" + java.net.URLEncoder.encode("cp06 fake:+密钥", java.nio.charset.StandardCharsets.UTF_8)));
            } else {
                String raw = new String(java.util.Base64.getDecoder().decode(tokenRequest.firstHeader("Authorization").substring(6)), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(java.net.URLEncoder.encode("client: +中", java.nio.charset.StandardCharsets.UTF_8) + ":"
                        + java.net.URLEncoder.encode("cp06 fake:+密钥", java.nio.charset.StandardCharsets.UTF_8), raw);
            }
            var attempts = o.worker.attempts(first);
            assertEquals("fixture-oauth-v1", attempts.get(0).path("credential_version").asText());
            String query = o.worker.api.get("/v1/notifications/" + first, OPERATOR_A).body();
            assertFalse(query.contains("environment-token")); assertFalse(query.contains("cp06 fake")); assertFalse(query.contains("digest"));
            o.evidence("cp06-env-" + method, first);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidTokenRecoveryIsPersistedAndUsesOneNewAttemptEvenAfterRestart(boolean restart) throws Exception {
        var issued = new AtomicInteger();
        try (var o = new OAuthFixture(properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("recovery-token-" + issued.incrementAndGet(), 3600)));
            o.worker.mock.route("/business", e -> {
                if (e.getRequestHeaders().getFirst("Authorization").equals("Bearer recovery-token-1")) {
                    e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired");
                } else HttpMock.respond(e, 200, "ok");
            });
            var id = o.submit("401-recovery-" + restart);
            var pending = executeNextAttempt(o.worker, id);
            assertEquals("pending", pending.path("status").asText());
            assertTrue(pending.path("auth_recovery_used").asBoolean()); assertTrue(pending.path("auth_recovery_required").asBoolean());
            assertEquals("HTTP_REJECTED", pending.path("last_outcome").asText());
            assertEquals(1, o.worker.mock.journal().size()); assertEquals(1, o.tokens.journal().size());
            assertFalse(o.worker.attempts(id).get(0).path("ended_at").isNull());
            o.evidence("cp06-recovery-" + restart + "-pending", id);
            if (restart) {
                o.worker.restart(Map.of());
                assertTrue(o.worker.task(id).path("auth_recovery_used").asBoolean());
                assertTrue(o.worker.task(id).path("auth_recovery_required").asBoolean());
            }
            var succeeded = executeNextAttempt(o.worker, id);
            assertEquals("succeeded", succeeded.path("status").asText());
            assertTrue(succeeded.path("auth_recovery_used").asBoolean()); assertFalse(succeeded.path("auth_recovery_required").asBoolean());
            assertEquals(2, succeeded.path("run_attempt_count").asInt());
            assertEquals(2, o.tokens.journal().size()); assertEquals(2, o.worker.mock.journal().size());
            assertEquals(o.worker.mock.journal().get(0).firstHeader("X-Event-Id"), o.worker.mock.journal().get(1).firstHeader("X-Event-Id"));
            assertArrayEquals(o.worker.mock.journal().get(0).body(), o.worker.mock.journal().get(1).body());
            o.evidence("cp06-recovery-" + restart + "-succeeded", id);
        }
    }

    @ParameterizedTest @CsvSource({"401,false", "403,true"})
    void ordinaryUnauthorizedAndForbiddenResponsesDoNotRefreshTokens(int status, boolean challenge) throws Exception {
        try (var o = new OAuthFixture(properties(), "client_secret_post")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("ordinary-token", 3600)));
            o.worker.mock.route("/business", e -> {
                if (challenge) e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                HttpMock.respond(e, status, "{\"error\":\"invalid_token\"}");
            });
            var id = o.submit("ordinary-auth-" + status);
            var failed = executeNextAttempt(o.worker, id);
            assertEquals("failed", failed.path("status").asText()); assertFalse(failed.path("auth_recovery_used").asBoolean());
            assertEquals(0, o.worker.worker.tick());
            assertEquals(1, o.tokens.journal().size()); assertEquals(1, o.worker.mock.journal().size());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Basic error=\"invalid_token\"", "Bearer realm=\"invalid_token\"",
            "Bearer error=\"invalid_token\", error=\"other\"", "Bearer,error=\"invalid_token\""})
    void malformedOrMisleadingChallengesDoNotCreateARecoveryAttempt(String challenge) throws Exception {
        try (var o = new OAuthFixture(properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("misleading-token", 3600)));
            o.worker.mock.route("/business", e -> { e.getResponseHeaders().set("WWW-Authenticate", challenge); HttpMock.respond(e, 401, "expired"); });
            var id = o.submit("misleading-challenge");
            var failed = executeNextAttempt(o.worker, id);
            assertEquals("failed", failed.path("status").asText()); assertFalse(failed.path("auth_recovery_used").asBoolean());
            assertEquals(1, o.tokens.journal().size()); assertEquals(1, o.worker.mock.journal().size());
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 12})
    void budgetExhaustionOrASecondExplicitRejectionCannotCreateAnotherRecovery(int budget) throws Exception {
        var properties = properties(); properties.put("app.delivery.max-attempts", Integer.toString(budget));
        var issued = new AtomicInteger();
        try (var o = new OAuthFixture(properties, "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("always-rejected-" + issued.incrementAndGet(), 3600)));
            o.worker.mock.route("/business", e -> { e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired"); });
            var id = o.submit("auth-budget-" + budget);
            var state = executeNextAttempt(o.worker, id);
            if (budget > 1) {
                assertEquals("pending", state.path("status").asText()); state = executeNextAttempt(o.worker, id);
                assertEquals("AUTH_RECOVERY_EXHAUSTED", state.path("defer_reason").asText());
            }
            assertEquals("failed", state.path("status").asText());
            int attempts = budget == 1 ? 1 : 2;
            assertEquals(attempts, state.path("run_attempt_count").asInt()); assertEquals(attempts, o.tokens.journal().size());
            assertEquals(attempts, o.worker.mock.journal().size()); assertEquals(0, o.worker.worker.tick());
            o.evidence("cp06-auth-budget-" + budget, id);
        }
    }

    @Test
    void aNewGenerationWithTheSameRejectedTokenStopsBeforeAnotherBusinessRequest() throws Exception {
        try (var o = new OAuthFixture(properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("unchanged-rejected-token", 3600)));
            o.worker.mock.route("/business", e -> { e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired"); });
            var id = o.submit("same-token-recovery"); executeNextAttempt(o.worker, id);
            var failed = executeNextAttempt(o.worker, id);
            assertEquals("failed", failed.path("status").asText());
            assertEquals("NOT_SENT", failed.path("last_outcome").asText());
            assertEquals("AUTH_REFRESH_REJECTED", failed.path("last_error_code").asText());
            assertEquals(2, failed.path("run_attempt_count").asInt()); assertEquals(2, o.tokens.journal().size());
            assertEquals(1, o.worker.mock.journal().size()); assertTrue(o.worker.attempts(id).get(1).path("request_started_at").isNull());
            o.evidence("cp06-same-rejected-token", id);
        }
    }

    @Test
    void transientTokenFailureDuringRecoveryPreservesItsIntentUntilANewTokenCanBeUsed() throws Exception {
        var issued = new AtomicInteger();
        try (var o = new OAuthFixture(properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> {
                int request = issued.incrementAndGet();
                if (request == 2) HttpMock.respond(e, 503, "temporary-auth-failure");
                else HttpMock.respond(e, 200, OAuthFixture.response("recovery-after-failure-" + request, 3600));
            });
            o.worker.mock.route("/business", e -> {
                if (e.getRequestHeaders().getFirst("Authorization").endsWith("-1")) {
                    e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired");
                } else HttpMock.respond(e, 200, "ok");
            });
            var id = o.submit("recovery-token-failure"); executeNextAttempt(o.worker, id);
            var pending = executeNextAttempt(o.worker, id);
            assertEquals("pending", pending.path("status").asText()); assertEquals("NOT_SENT", pending.path("last_outcome").asText());
            assertEquals("TOKEN_SERVER_ERROR", pending.path("last_error_code").asText());
            assertTrue(pending.path("auth_recovery_used").asBoolean()); assertTrue(pending.path("auth_recovery_required").asBoolean());
            assertEquals(2, pending.path("run_attempt_count").asInt()); assertEquals(1, o.worker.mock.journal().size());
            o.evidence("cp06-recovery-token-failure-pending", id);
            var success = executeNextAttempt(o.worker, id);
            assertEquals("succeeded", success.path("status").asText()); assertEquals(3, success.path("run_attempt_count").asInt());
            assertEquals(3, o.tokens.journal().size()); assertEquals(2, o.worker.mock.journal().size());
            o.evidence("cp06-recovery-token-failure-succeeded", id);
        }
    }

    @ParameterizedTest @CsvSource({"400,invalid_client,TOKEN_INVALID_CLIENT", "400,unauthorized_client,TOKEN_UNAUTHORIZED_CLIENT",
            "400,invalid_scope,TOKEN_INVALID_SCOPE"})
    void definitiveCredentialErrorsFailWithoutSendingBusinessHttp(int status, String error, String code) throws Exception {
        try (var o = new OAuthFixture(properties(), "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, status, "{\"error\":\"" + error + "\"}"));
            o.worker.mock.route("/business", e -> HttpMock.respond(e, 200, "must-not-arrive"));
            var id = o.submit("invalid-credential-" + error);
            var failed = executeNextAttempt(o.worker, id);
            assertEquals("failed", failed.path("status").asText()); assertEquals("NOT_SENT", failed.path("last_outcome").asText());
            assertEquals(code, failed.path("last_error_code").asText()); assertEquals(1, failed.path("run_attempt_count").asInt());
            assertEquals(0, o.worker.mock.journal().size()); assertEquals(1, o.tokens.journal().size());
        }
    }

    @Test
    void delayedOld401DoesNotDeleteTheNewTokenAcquiredByAnotherTasksRecovery() throws Exception {
        var properties = properties(); properties.put("app.worker.global-concurrency", "2");
        properties.put("app.worker.business-timeout", "3s"); properties.put("app.worker.attempt-timeout", "4s"); properties.put("app.worker.lease-duration", "6s");
        var issued = new AtomicInteger(); var oldArrived = new CountDownLatch(1); var releaseOld = new CountDownLatch(1);
        try (var o = new OAuthFixture(properties, "client_secret_basic")) {
            o.tokens.route("/token", e -> HttpMock.respond(e, 200, OAuthFixture.response("race-token-" + issued.incrementAndGet(), 3600)));
            o.worker.mock.route("/business", e -> {
                boolean old = e.getRequestHeaders().getFirst("Authorization").equals("Bearer race-token-1");
                if (old && e.getRequestHeaders().getFirst("X-Event-Id").equals("late-first")) {
                    oldArrived.countDown();
                    try { assertTrue(releaseOld.await(4, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (old) { e.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\""); HttpMock.respond(e, 401, "expired"); }
                else HttpMock.respond(e, 200, "ok");
            });
            var first = o.submit("late-first"); o.worker.worker.tick(); assertTrue(oldArrived.await(2, TimeUnit.SECONDS));
            var second = o.submit("early-second"); assertTrue(o.worker.worker.tick() > 0);
            o.worker.awaitStatus(second, "pending", Duration.ofSeconds(3));
            awaitDue(o.worker, second, Duration.ofSeconds(3)); assertTrue(o.worker.worker.tick() > 0);
            o.worker.awaitStatus(second, "succeeded", Duration.ofSeconds(3));
            assertEquals(2, o.tokens.journal().size());
            releaseOld.countDown(); o.worker.awaitStatus(first, "pending", Duration.ofSeconds(3));
            awaitDue(o.worker, first, Duration.ofSeconds(3)); assertTrue(o.worker.worker.tick() > 0);
            o.worker.awaitStatus(first, "succeeded", Duration.ofSeconds(3)); o.worker.awaitIdle(Duration.ofSeconds(2));
            assertEquals(2, o.tokens.journal().size(), "The delayed old generation cannot invalidate the already refreshed token");
            assertEquals(4, o.worker.mock.journal().size());
            assertEquals(2, o.worker.attempts(first).size()); assertEquals(2, o.worker.attempts(second).size());
            o.evidence("cp06-delayed-old-401", first);
        } finally { releaseOld.countDown(); }
    }
}
