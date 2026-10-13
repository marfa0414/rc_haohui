package com.rc.notification.api;

import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import com.rc.notification.support.TcpFaultProxy;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP02 TC-API-02: actual rollback, JDBC connection loss, and committed response lost on the wire. */
@Timeout(60)
class NotificationAcceptanceFailureTest {
    @Test
    void anAfterInsertDatabaseFailureRollsBackInsteadOfReturning202() throws Exception {
        try (var api = new NotificationApiFixture()) {
            api.sql("CREATE FUNCTION test_rollback_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'test-only-rollback-sensitive-sentinel'; END $$");
            api.sql("CREATE TRIGGER test_rollback_insert AFTER INSERT ON notification_task "
                    + "FOR EACH ROW EXECUTE FUNCTION test_rollback_insert()");
            String event = payload("tenant-a", "user.registered", "rollback-event", "{}");
            var failed = api.post(event, CALLER_A);
            NotificationAccessAndQueryTest.assertError(api, failed, 503, "STORAGE_UNAVAILABLE");
            assertFalse(failed.body().contains("test-only-rollback-sensitive-sentinel"));
            assertEquals(0, api.taskCount(), "AFTER INSERT failure must roll back the inserted row");
            api.sql("DROP TRIGGER test_rollback_insert ON notification_task");
            assertEquals(202, api.post(event, CALLER_A).statusCode());
            assertEquals(1, api.taskCount());
        }
    }

    @Test
    void disconnectedDatabaseCannotAcknowledgeAndReadinessRecovers() throws Exception {
        RealPostgres database = new RealPostgres();
        try (var proxy = new TcpFaultProxy(database.jdbcUrl());
                var api = new NotificationApiFixture(database, "api", Map.of(
                        "spring.datasource.url", proxy.jdbcUrl(),
                        "spring.datasource.hikari.connection-timeout", "1000",
                        "spring.datasource.hikari.validation-timeout", "500"))) {
            assertEquals(200, api.get("/health/ready", null).statusCode());
            proxy.cut();
            String event = payload("tenant-a", "user.registered", "database-down-event", "{}");
            var rejected = api.post(event, CALLER_A);
            NotificationAccessAndQueryTest.assertError(api, rejected, 503, "STORAGE_UNAVAILABLE");
            assertEquals(0, api.taskCount(), "A separate direct connection verifies no row was accepted");
            assertEquals(503, api.get("/health/ready", null).statusCode());
            assertEquals(200, api.get("/health/live", null).statusCode());
            proxy.restore();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            int ready = 503;
            while (System.nanoTime() < deadline) {
                ready = api.get("/health/ready", null).statusCode();
                if (ready == 200) break;
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            }
            assertEquals(200, ready, "Readiness must recover after the actual JDBC path is restored");
            assertEquals(202, api.post(event, CALLER_A).statusCode());
            assertEquals(1, api.taskCount());
        }
    }

    @Test
    void aCommittedResponseLostOnTheWireCanBeResolvedByTheOriginalEventKey() throws Exception {
        try (var api = new NotificationApiFixture(); var lossyProxy = new HttpMock()) {
            String event = payload("tenant-a", "user.registered", "lost-response-event", "{\"user\":123}");
            AtomicReference<HttpResponse<String>> committedResponse = new AtomicReference<>();
            CountDownLatch committed = new CountDownLatch(1);
            AtomicReference<Throwable> proxyFailure = new AtomicReference<>();
            lossyProxy.route("/submit", exchange -> {
                try {
                    // The proxy sees the real app's response, then drops it before sending any response headers upstream.
                    var upstream = api.post(event, CALLER_A);
                    committedResponse.compareAndSet(null, upstream);
                    committed.countDown();
                } catch (Throwable failed) {
                    proxyFailure.set(failed);
                } finally {
                    exchange.close();
                }
            });
            var request = HttpRequest.newBuilder(lossyProxy.uri("/submit")).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(event)).build();
            assertThrows(IOException.class, () -> HTTP.send(request, HttpResponse.BodyHandlers.ofString()));
            assertTrue(committed.await(2, TimeUnit.SECONDS));
            assertNull(proxyFailure.get());
            assertEquals(202, committedResponse.get().statusCode());
            assertEquals(1, api.taskCount());
            var retry = api.post(event, CALLER_A);
            assertEquals(200, retry.statusCode(), retry.body());
            assertTrue(api.json(retry).path("deduplicated").asBoolean());
            assertEquals(api.json(committedResponse.get()).path("notificationId"), api.json(retry).path("notificationId"));
            assertEquals(1, api.taskCount());
        }
    }
}
