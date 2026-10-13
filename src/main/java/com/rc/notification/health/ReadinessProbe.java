package com.rc.notification.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReadinessProbe {
    private static final String EXPECTED_SCHEMA_VERSION = "6";
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final ApplicationAvailability availability;

    public ReadinessProbe(JdbcTemplate jdbc, Flyway flyway, ApplicationAvailability availability) {
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.availability = availability;
    }

    public boolean isReady() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return false;
        }
        try {
            Boolean tablesPresent = jdbc.queryForObject("""
                    SELECT to_regclass('notification_template_version') IS NOT NULL
                       AND to_regclass('auth_profile') IS NOT NULL
                       AND to_regclass('notification_route') IS NOT NULL
                       AND to_regclass('audit_event') IS NOT NULL
                       AND to_regclass('notification_task') IS NOT NULL
                       AND to_regclass('notification_attempt') IS NOT NULL
                       AND to_regclass('replay_request') IS NOT NULL
                    """, Boolean.class);
            if (!Boolean.TRUE.equals(tablesPresent) || !flyway.validateWithResult().validationSuccessful) {
                return false;
            }
            MigrationInfo current = flyway.info().current();
            return current != null && current.getVersion() != null
                    && EXPECTED_SCHEMA_VERSION.equals(current.getVersion().getVersion());
        } catch (RuntimeException unavailable) {
            // Readiness deliberately exposes no database locations, credentials, or exception text.
            return false;
        }
    }
}
