package com.rc.notification.runtime;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Runtime validates an already migrated schema. Only explicit test/development setup may migrate at startup. */
@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {
    public static final String EXPECTED_SCHEMA_VERSION = "6";

    @Bean
    FlywayMigrationStrategy databaseMigrationStrategy(Environment environment) {
        String mode = environment.getProperty("app.database.migration-mode", "validate");
        if (!mode.equals("validate") && !mode.equals("migrate")) {
            throw new IllegalArgumentException("Invalid database migration mode");
        }
        return flyway -> {
            if (mode.equals("migrate")) flyway.migrate();
            validate(flyway);
        };
    }

    public static void validate(Flyway flyway) {
        if (!flyway.validateWithResult().validationSuccessful) {
            throw new IllegalStateException("DATABASE_SCHEMA_VALIDATION_FAILED");
        }
        var current = flyway.info().current();
        if (current == null || current.getVersion() == null
                || !EXPECTED_SCHEMA_VERSION.equals(current.getVersion().getVersion())) {
            throw new IllegalStateException("DATABASE_SCHEMA_VERSION_MISMATCH");
        }
    }
}
