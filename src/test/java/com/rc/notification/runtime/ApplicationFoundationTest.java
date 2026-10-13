package com.rc.notification.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.NotificationApplication;
import com.rc.notification.support.RealPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** CP01 portions of TC-OBS-03 and TC-OPS-01; business roles have no handlers or scheduler yet. */
@Timeout(60)
class ApplicationFoundationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    @ParameterizedTest
    @CsvSource({"api,true,false", "worker,false,true", "all,true,true"})
    void enablesOnlyTheSelectedRolesAndExposesHealthyEndpoints(String mode, boolean api, boolean worker) throws Exception {
        try (RealPostgres database = new RealPostgres(); var application = start(database, mode)) {
            assertEquals(api ? 1 : 0, application.getBeansOfType(RoleConfiguration.ApiRole.class).size());
            assertEquals(worker ? 1 : 0, application.getBeansOfType(RoleConfiguration.WorkerRole.class).size());
            assertHealth(application, "/health/live", 200, "UP");
            assertHealth(application, "/health/ready", 200, "UP");
            assertTrue(database.historyCount() > 0);
        }
    }

    @Test
    void aSecondApplicationStartDoesNotRepeatMigration() throws Exception {
        try (RealPostgres database = new RealPostgres()) {
            int migrations;
            try (var first = start(database, "all")) {
                assertHealth(first, "/health/ready", 200, "UP");
                migrations = database.historyCount();
            }
            try (var second = start(database, "worker")) {
                assertHealth(second, "/health/ready", 200, "UP");
                assertEquals(migrations, database.historyCount());
            }
        }
    }

    @Test
    void readinessBecomesDownOnSchemaMismatchWithoutFailingLiveness() throws Exception {
        try (RealPostgres database = new RealPostgres(); var application = start(database, "api")) {
            assertHealth(application, "/health/ready", 200, "UP");
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE " + database.schema()
                        + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'"));
            }
            assertHealth(application, "/health/ready", 503, "DOWN");
            assertHealth(application, "/health/live", 200, "UP");
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + database.schema()
                        + ".flyway_schema_history SET checksum = checksum - 1 WHERE version = '1'");
            }
            assertHealth(application, "/health/ready", 200, "UP");
        }
    }

    @Test
    void readinessRejectsMissingRequiredTablesAndRecoversWhenRestored() throws Exception {
        try (RealPostgres database = new RealPostgres(); var application = start(database, "all")) {
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + database.schema() + ".audit_event RENAME TO audit_event_test_hidden");
            }
            assertHealth(application, "/health/ready", 503, "DOWN");
            assertHealth(application, "/health/live", 200, "UP");
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + database.schema() + ".audit_event_test_hidden RENAME TO audit_event");
            }
            assertHealth(application, "/health/ready", 200, "UP");
        }
    }

    @Test
    void readinessRejectsANewerSchemaVersion() throws Exception {
        try (RealPostgres database = new RealPostgres(); var application = start(database, "worker")) {
            try (Connection connection = database.connection(); var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE " + database.schema()
                        + ".flyway_schema_history SET version = '999' WHERE version = '1'"));
            }
            assertHealth(application, "/health/ready", 503, "DOWN");
            assertHealth(application, "/health/live", 200, "UP");
        }
    }

    @Test
    void rejectsInvalidRuntimeModeAtStartup() throws Exception {
        try (RealPostgres database = new RealPostgres()) {
            assertThrows(RuntimeException.class, () -> {
                try (var ignored = start(database, "not-a-mode")) {
                    fail("An unknown role must not silently enable api and worker");
                }
            });
        }
    }

    private static ConfigurableApplicationContext start(RealPostgres database, String mode) {
        return new SpringApplicationBuilder(NotificationApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("isolated-real-postgres-test", database.springProperties(mode))))
                .run();
    }

    private static void assertHealth(ConfigurableApplicationContext context, String path, int expectedStatus,
            String expectedHealth) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), path + " returned " + response.body());
        var json = JSON.readTree(response.body());
        assertEquals(expectedHealth, json.path("status").asText());
        assertEquals(1, json.size(), "Health responses must not reveal DB connection details or exception messages");
    }
}
