package com.rc.notification.runtime;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** A separate, terminating entry point: no HTTP server, scheduler, or application credentials are loaded. */
public final class MigrationBootstrap {
    private MigrationBootstrap() { }

    public static int migrate(Map<String, String> environment) {
        String url = required(environment, "DATABASE_URL");
        String user = required(environment, "DATABASE_USERNAME");
        String password = required(environment, "DATABASE_PASSWORD");
        String schema = identifier(required(environment, "DATABASE_SCHEMA"));
        String role = identifier(required(environment, "DATABASE_APPLICATION_ROLE"));
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Invalid database protocol");
        try {
            var flyway = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).locations("classpath:db/migration").cleanDisabled(true)
                    .validateOnMigrate(true).validateMigrationNaming(true).baselineOnMigrate(false).load();
            int executed = flyway.migrate().migrationsExecuted;
            MigrationConfiguration.validate(flyway);
            grantRuntimePrivileges(url, user, password, schema, role);
            return executed;
        } catch (Exception failure) {
            throw new IllegalStateException("DATABASE_MIGRATION_FAILED");
        }
    }

    private static void grantRuntimePrivileges(String url, String user, String password, String schema, String role)
            throws Exception {
        String quotedSchema = '"' + schema + '"';
        String quotedRole = '"' + role + '"';
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + quotedSchema + " FROM " + quotedRole);
            statement.execute("REVOKE CREATE ON SCHEMA " + quotedSchema + " FROM " + quotedRole);
            statement.execute("GRANT USAGE ON SCHEMA " + quotedSchema + " TO " + quotedRole);
            for (String table : List.of("notification_template_version", "auth_profile", "notification_route",
                    "notification_task", "notification_attempt")) {
                statement.execute("GRANT SELECT,INSERT,UPDATE ON " + quotedSchema + "." + table + " TO " + quotedRole);
            }
            for (String table : List.of("audit_event", "replay_request")) {
                statement.execute("GRANT SELECT,INSERT ON " + quotedSchema + "." + table + " TO " + quotedRole);
            }
            statement.execute("GRANT SELECT ON " + quotedSchema + ".flyway_schema_history TO " + quotedRole);
            connection.commit();
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing database migration setting");
        return value;
    }

    private static String identifier(String value) {
        if (!value.matches("[a-z_][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid database identifier");
        return value;
    }
}
