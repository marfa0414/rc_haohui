package com.rc.notification.runtime;

import static com.rc.notification.support.ConfigurationFixture.apiKey;
import static com.rc.notification.support.ConfigurationFixture.scalarTemplate;
import static com.rc.notification.support.NotificationApiFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.rc.notification.support.ConfigurationFixture;
import com.rc.notification.support.HttpMock;
import com.rc.notification.support.NotificationApiFixture;
import com.rc.notification.support.RealPostgres;
import com.rc.notification.worker.WorkerRuntime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP10: real separate migration/application roles; the runtime never receives the migration password. */
@Timeout(75)
class DeploymentMigrationTest {
    @Test
    void migrationEntryPointOwnsDdlAndIsRepeatableWithoutReapplyingVersions() throws Exception {
        try (var roles = new RoleDatabase()) {
            assertEquals(6, MigrationBootstrap.migrate(roles.migrationEnvironment()));
            assertEquals(6, roles.database.historyCount());
            assertEquals(0, MigrationBootstrap.migrate(roles.migrationEnvironment()));
            assertEquals(6, roles.database.historyCount());
            try (var connection = roles.applicationConnection(); var query = connection.createStatement()) {
                try (var rows = query.executeQuery("SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls "
                        + "FROM pg_roles WHERE rolname=current_user")) {
                    assertTrue(rows.next());
                    assertEquals(roles.applicationRole, rows.getString(1));
                    for (int column = 2; column <= 6; column++) assertFalse(rows.getBoolean(column));
                }
                try (var rows = query.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='"
                        + roles.database.schema() + "'")) {
                    int count = 0;
                    while (rows.next()) {
                        assertEquals(roles.migrationRole, rows.getString(1));
                        count++;
                    }
                    assertEquals(8, count, "Seven application tables and the Flyway history belong to the migration role");
                }
                try (var rows = query.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
                    assertTrue(rows.next());
                    assertEquals(6, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void validateOnlyStartupRejectsAnEmptySchemaAndCannotSilentlyMigrateIt() throws Exception {
        try (var roles = new RoleDatabase()) {
            assertThrows(RuntimeException.class, () -> {
                try (var ignored = new NotificationApiFixture(roles.database, "api", roles.runtimeProperties(), false)) {
                    fail("Runtime validation must reject an unmigrated schema");
                }
            });
            try (var connection = roles.database.connection(); var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
                            + roles.database.schema() + "'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "No history or business table may be created by runtime startup");
            }
        }
    }

    @Test
    void lowPrivilegeRuntimeCanPublishAcceptDeliverAndReadHealthButCannotAlterProtectedPersistence() throws Exception {
        try (var roles = new RoleDatabase(); var mock = new HttpMock()) {
            MigrationBootstrap.migrate(roles.migrationEnvironment());
            mock.route("/notify", exchange -> HttpMock.respond(exchange, 200, "accepted"));
            var properties = new HashMap<String, Object>(roles.runtimeProperties());
            properties.putAll(Map.of("app.environment", "test", "app.worker.scheduling-enabled", "false",
                    "app.outbound.allowed-hosts", "127.0.0.1", "app.outbound.development-http-hosts", "127.0.0.1",
                    "app.outbound.development-http-ports", Integer.toString(mock.uri("/notify").getPort()),
                    "app.outbound.development-allowed-cidrs", "127.0.0.1/32"));
            // The role fixture owns schema cleanup, so close only the application context here.
            var api = new NotificationApiFixture(roles.database, "all", properties, false);
            try {
                assertEquals(roles.applicationRole,
                        api.application.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
                assertEquals(200, api.get("/health/ready", null).statusCode());
                var configuration = new ConfigurationFixture(api);
                var published = configuration.install(scalarTemplate("tenant-a", "least-privilege")
                                .put("url", mock.uri("/notify").toString()),
                        apiKey("tenant-a").put("secretRef", "env:CP04_TEST_SECRET"), "privilege.notify");
                var accepted = api.post(payload("tenant-a", "privilege.notify", "least-privilege-event", "{}"), ADMIN);
                assertEquals(202, accepted.statusCode(), accepted.body());
                UUID id = UUID.fromString(api.json(accepted).path("notificationId").asText());
                var worker = api.application.getBean(WorkerRuntime.class);
                assertEquals(1, worker.tick());
                long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                String status = "pending";
                do {
                    status = api.json(api.get("/v1/notifications/" + id, ADMIN)).path("status").asText();
                    if (status.equals("succeeded") && worker.isIdle()) break;
                    LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
                } while (System.nanoTime() < deadline);
                assertEquals("succeeded", status);
                assertTrue(worker.isIdle());
                assertEquals(1, mock.count("/notify"));
                assertEquals(6, roles.database.historyCount());
                assertEquals(200, api.get("/health/ready", null).statusCode());

                for (String sql : List.of("CREATE TABLE forbidden_ddl(id integer)",
                        "ALTER TABLE notification_task DISABLE TRIGGER ALL", "DROP TABLE replay_request",
                        "TRUNCATE notification_attempt", "DELETE FROM notification_attempt", "DELETE FROM notification_task",
                        "UPDATE audit_event SET action='FORBIDDEN'", "DELETE FROM audit_event", "TRUNCATE audit_event",
                        "UPDATE flyway_schema_history SET checksum=0", "DELETE FROM flyway_schema_history",
                        "INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) "
                                + "VALUES(99,'99','forbidden','SQL','forbidden','forbidden',0,true)")) {
                    assertSqlState(roles, sql, "42501");
                }
                assertSqlState(roles, "UPDATE notification_task SET body_bytes=convert_to('tampered','UTF8') WHERE id='"
                        + id + "'", "23514");
                assertSqlState(roles, "UPDATE notification_template_version SET revision=revision+1 WHERE id='"
                        + published.template().path("id").asText() + "'", "23514");
                assertSqlState(roles, "UPDATE auth_profile SET revision=revision+1 WHERE id='"
                        + published.auth().path("id").asText() + "'", "23514");
                assertEquals("succeeded", api.json(api.get("/v1/notifications/" + id, ADMIN)).path("status").asText());
                assertEquals(6, roles.database.historyCount());
                assertEquals(200, api.get("/health/ready", null).statusCode());
            } finally {
                api.application.close();
            }
        }
    }

    private static void assertSqlState(RoleDatabase roles, String sql, String expected) {
        var failure = assertThrows(SQLException.class, () -> {
            try (var connection = roles.applicationConnection(); var query = connection.createStatement()) {
                query.execute(sql);
            }
        });
        assertEquals(expected, failure.getSQLState(), sql);
    }

    private static final class RoleDatabase implements AutoCloseable {
        private static final String PASSWORD = "cp10-role-password-test-only";
        final RealPostgres database = new RealPostgres();
        final String migrationRole = "cp10_m_" + UUID.randomUUID().toString().replace("-", "");
        final String applicationRole = "cp10_a_" + UUID.randomUUID().toString().replace("-", "");

        RoleDatabase() throws Exception {
            try (var connection = database.connection(); var statement = connection.createStatement()) {
                for (String role : List.of(migrationRole, applicationRole)) statement.execute("CREATE ROLE " + role
                        + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '" + PASSWORD + "'");
                statement.execute("ALTER SCHEMA " + database.schema() + " OWNER TO " + migrationRole);
                statement.execute("REVOKE ALL ON SCHEMA " + database.schema() + " FROM PUBLIC");
                statement.execute("GRANT USAGE ON SCHEMA " + database.schema() + " TO " + applicationRole);
            }
        }

        Map<String, String> migrationEnvironment() {
            return Map.of("DATABASE_URL", database.jdbcUrl(), "DATABASE_USERNAME", migrationRole,
                    "DATABASE_PASSWORD", PASSWORD, "DATABASE_SCHEMA", database.schema(),
                    "DATABASE_APPLICATION_ROLE", applicationRole);
        }

        Map<String, Object> runtimeProperties() {
            return Map.of("spring.datasource.username", applicationRole, "spring.datasource.password", PASSWORD,
                    "app.database.migration-mode", "validate");
        }

        Connection applicationConnection() throws SQLException {
            Connection connection = DriverManager.getConnection(database.jdbcUrl(), applicationRole, PASSWORD);
            connection.setSchema(database.schema());
            return connection;
        }

        @Override public void close() throws Exception {
            try {
                database.close();
            } finally {
                try (var connection = database.connection(); var statement = connection.createStatement()) {
                    for (String role : List.of(applicationRole, migrationRole)) {
                        statement.execute("DROP OWNED BY " + role);
                        statement.execute("DROP ROLE " + role);
                    }
                }
            }
        }
    }
}
