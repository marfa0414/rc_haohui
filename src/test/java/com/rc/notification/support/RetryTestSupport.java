package com.rc.notification.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

public final class RetryTestSupport {
    private RetryTestSupport() { }
    public static Map<String, Object> fastRetries() {
        return Map.of("app.retry.base-delay", "5ms", "app.retry.max-delay", "10ms", "app.worker.global-concurrency", "1");
    }
    public static JsonNode executeNextAttempt(WorkerFixture fixture, UUID id) throws Exception {
        awaitDue(fixture, id, Duration.ofSeconds(8));
        assertTrue(fixture.worker.tick() > 0, "An actual due task must be claimed");
        fixture.awaitIdle(Duration.ofSeconds(6));
        return fixture.task(id);
    }
    public static void awaitDue(WorkerFixture fixture, UUID id, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        do {
            try (var connection = fixture.api.database.connection(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT status='pending' AND next_attempt_at<=clock_timestamp() FROM "
                            + fixture.api.database.schema() + ".notification_task WHERE id='" + id + "'")) {
                assertTrue(rows.next());
                if (rows.getBoolean(1)) return;
            }
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        } while (System.nanoTime() < end);
        fail("Task did not become due: " + fixture.task(id));
    }
}
