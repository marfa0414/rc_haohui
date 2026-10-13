package com.rc.notification.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/** A unique schema in a real PostgreSQL instance. Missing infrastructure is a test failure. */
public final class RealPostgres implements AutoCloseable {
    private final String url;
    private final String user;
    private final String password;
    private final String schema = "cp01_" + UUID.randomUUID().toString().replace("-", "");

    public RealPostgres() throws SQLException {
        url = required("test.database.url", "TEST_DATABASE_URL");
        user = required("test.database.user", "TEST_DATABASE_USER");
        password = System.getProperty("test.database.password",
                System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("CP01 requires real PostgreSQL: TEST_DATABASE_URL must use jdbc:postgresql:");
        }
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private static String required(String property, String environment) {
        String value = System.getProperty(property, System.getenv(environment));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environment + " (or -D" + property
                    + ") is required. Database tests must fail, never skip, when infrastructure is unavailable.");
        }
        return value;
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .locations("classpath:db/migration")
                .load();
    }

    public String schema() {
        return schema;
    }

    public String jdbcUrl() {
        return url;
    }

    public Map<String, Object> springProperties(String mode) {
        return Map.ofEntries(
                Map.entry("server.address", "127.0.0.1"),
                Map.entry("server.port", "0"),
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("spring.datasource.url", url),
                Map.entry("spring.datasource.username", user),
                Map.entry("spring.datasource.password", password),
                Map.entry("spring.datasource.hikari.schema", schema),
                Map.entry("spring.datasource.hikari.maximum-pool-size", "2"),
                Map.entry("spring.datasource.hikari.connection-timeout", "1000"),
                Map.entry("spring.flyway.schemas", schema),
                Map.entry("spring.flyway.default-schema", schema),
                Map.entry("spring.flyway.create-schemas", "false"),
                // Test-only setup: runtime deployments validate and use a separate migration process.
                Map.entry("app.database.migration-mode", "migrate"),
                Map.entry("app.mode", mode));
    }

    public int historyCount() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement();
                var results = statement.executeQuery("SELECT count(*) FROM " + schema
                        + ".flyway_schema_history WHERE success AND type = 'SQL'")) {
            results.next();
            return results.getInt(1);
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            // The name is generated above, never supplied by the caller; only this test's schema is removed.
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
