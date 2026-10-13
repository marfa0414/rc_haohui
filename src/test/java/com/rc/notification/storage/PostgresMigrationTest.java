package com.rc.notification.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.RealPostgres;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CP01: all migrations execute against real PostgreSQL, never an in-memory substitute. */
@Timeout(45)
class PostgresMigrationTest {
    @Test
    void migratesAnEmptySchemaAndDoesNotRepeatAppliedMigrations() throws Exception {
        try (RealPostgres database = new RealPostgres()) {
            var first = database.flyway().migrate();
            assertTrue(first.success);
            assertTrue(first.migrationsExecuted > 0, "At least the initial schema must actually execute");
            assertEquals(first.migrationsExecuted, database.historyCount());
            assertExpectedFoundationTables(database);

            var second = database.flyway().migrate();
            assertTrue(second.success);
            assertEquals(0, second.migrationsExecuted);
            assertEquals(first.migrationsExecuted, database.historyCount());
            assertTrue(database.flyway().validateWithResult().validationSuccessful);
        }
    }

    @Test
    void twoMigratorsSafelyCompeteForOneEmptySchema() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (RealPostgres database = new RealPostgres()) {
            var operation = (java.util.concurrent.Callable<Integer>) () -> {
                var flyway = database.flyway();
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return flyway.migrate().migrationsExecuted;
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int totalExecuted = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

            assertTrue(totalExecuted > 0);
            assertEquals(database.historyCount(), totalExecuted, "Every migration executes exactly once across both contenders");
            assertEquals(0, database.flyway().migrate().migrationsExecuted);
            assertExpectedFoundationTables(database);
            assertTrue(database.flyway().validateWithResult().validationSuccessful);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void schemaHistoryMismatchIsAnErrorNotASilentRepair() throws Exception {
        try (RealPostgres database = new RealPostgres()) {
            database.flyway().migrate();
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE " + database.schema()
                        + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'"));
            }

            assertFalse(database.flyway().validateWithResult().validationSuccessful);
            assertThrows(FlywayException.class, () -> database.flyway().migrate());
        }
    }

    private static void assertExpectedFoundationTables(RealPostgres database) throws Exception {
        try (Connection connection = database.connection();
                var statement = connection.prepareStatement("SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?")) {
            for (String table : List.of("notification_template_version", "auth_profile", "notification_route", "audit_event", "notification_task", "notification_attempt")) {
                statement.setString(1, database.schema());
                statement.setString(2, table);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1), "Missing foundation table: " + table);
                }
            }
        }
    }
}
