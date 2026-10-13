package com.rc.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.rc.notification.NotificationApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** CP02 real application and real PostgreSQL. No repository or snapshot factory is substituted. */
public final class NotificationApiFixture implements AutoCloseable {
    // Admin responses may contain PostgreSQL-expanded numeric constants up to the configuration limit.
    // This client reader does not replace or relax the production request parser's 1000-character limit.
    public static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNumberLength(262144).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    public static final String CALLER_A = "cp02-business-a-test-only";
    public static final String CALLER_B = "cp02-business-b-test-only";
    public static final String ADMIN = "cp02-admin-test-only";
    public static final String LIMITED = "cp02-limited-test-only";
    public static final String CONFIG_A = "cp03-config-a-test-only";
    public static final String CONFIG_B = "cp03-config-b-test-only";
    public static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public final RealPostgres database;
    public final ConfigurableApplicationContext application;
    public final URI baseUri;

    public NotificationApiFixture() throws Exception {
        this(new RealPostgres(), "api", Map.of());
    }

    public NotificationApiFixture(RealPostgres database, String mode, Map<String, Object> overrides) throws Exception {
        this(database, mode, overrides, new Class<?>[0]);
    }

    public NotificationApiFixture(RealPostgres database, String mode, Map<String, Object> overrides, Class<?>... testSources) throws Exception {
        this.database = database;
        Map<String, Object> properties = new HashMap<>(database.springProperties(mode));
        properties.put("app.outbound.allowed-hosts", "supplier.invalid,supplier-two.invalid,oauth.invalid");
        caller(properties, 0, "caller-a", CALLER_A, "BUSINESS", "tenant-a", "user.registered,order.paid");
        caller(properties, 1, "caller-b", CALLER_B, "BUSINESS", "tenant-b", "user.registered,order.paid");
        caller(properties, 2, "admin", ADMIN, "ADMIN", "*", "*");
        caller(properties, 3, "limited", LIMITED, "BUSINESS", "tenant-a", "order.paid");
        caller(properties, 4, "config-a", CONFIG_A, "CONFIG_ADMIN", "tenant-a", "*");
        caller(properties, 5, "config-b", CONFIG_B, "CONFIG_ADMIN", "tenant-b", "*");
        properties.putAll(overrides);
        application = new SpringApplicationBuilder(NotificationApplication.class).web(WebApplicationType.SERVLET)
                .sources(testSources)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("real-api-test", properties)))
                .run();
        int port = ((ServletWebServerApplicationContext) application).getWebServer().getPort();
        baseUri = URI.create("http://127.0.0.1:" + port);
        route("tenant-a", "user.registered");
        route("tenant-a", "order.paid");
        route("tenant-b", "user.registered");
        route("tenant-b", "order.paid");
    }

    private static void caller(Map<String, Object> target, int index, String id, String token, String role,
            String tenants, String operations) {
        String prefix = "app.security.callers[" + index + "].";
        target.put(prefix + "id", id);
        target.put(prefix + "token", token);
        target.put(prefix + "roles", role);
        target.put(prefix + "tenants", tenants);
        target.put(prefix + "operations", operations);
    }

    public void route(String tenant, String operation) throws SQLException {
        route(tenant, operation, "published", "published", "https://supplier.invalid/notify");
    }

    public void route(String tenant, String operation, String templateStatus, String authStatus, String url)
            throws SQLException {
        UUID template = UUID.randomUUID();
        UUID auth = UUID.randomUUID();
        try (var connection = database.connection()) {
            connection.setSchema(database.schema());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO notification_template_version
                      (id,template_key,version,status,owner_tenant_id,provider_id,endpoint_group,config,
                       created_by,published_by,published_at)
                    VALUES (?, ?, 1, ?, ?, 'supplier', 'notify', ?::jsonb, 'test-fixture',
                            CASE WHEN ? = 'published' THEN 'test-fixture' END,
                            CASE WHEN ? = 'published' THEN clock_timestamp() END)
                    """)) {
                statement.setObject(1, template);
                statement.setString(2, "fixture-" + template);
                statement.setString(3, templateStatus);
                statement.setString(4, tenant);
                statement.setString(5, "{\"method\":\"POST\",\"url\":\"" + url
                        + "\",\"headers\":{\"X-Source\":{\"constant\":\"cp02\"}},"
                        + "\"body\":{\"format\":\"json\",\"fields\":{\"marker\":{\"constant\":\"cp02\"}}}}");
                statement.setString(6, templateStatus);
                statement.setString(7, templateStatus);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO auth_profile
                      (id,tenant_id,profile_key,version,status,auth_type,parameters,secret_ref,
                       created_by,published_by,published_at)
                    VALUES (?, ?, ?, 1, ?, 'api_key', '{"headerName":"X-Api-Key"}',
                            'env:CP02_VENDOR_SECRET', 'test-fixture',
                            CASE WHEN ? = 'published' THEN 'test-fixture' END,
                            CASE WHEN ? = 'published' THEN clock_timestamp() END)
                    """)) {
                statement.setObject(1, auth);
                statement.setString(2, tenant);
                statement.setString(3, "fixture-" + auth);
                statement.setString(4, authStatus);
                statement.setString(5, authStatus);
                statement.setString(6, authStatus);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO notification_route(tenant_id,operation,template_version_id,auth_profile_id,updated_by)
                    VALUES (?, ?, ?, ?, 'test-fixture')
                    """)) {
                statement.setString(1, tenant);
                statement.setString(2, operation);
                statement.setObject(3, template);
                statement.setObject(4, auth);
                statement.executeUpdate();
            }
        }
    }

    public static String payload(String tenant, String operation, String event, String data) {
        return "{\"tenantId\":\"" + tenant + "\",\"operation\":\"" + operation
                + "\",\"eventId\":\"" + event + "\",\"data\":" + data + "}";
    }

    public HttpResponse<String> submit(String data) throws Exception {
        return post(payload("tenant-a", "user.registered", "event-" + UUID.randomUUID(), data), CALLER_A);
    }

    public HttpResponse<String> post(String payload, String token) throws Exception {
        var request = request("/v1/notifications", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path, String token) throws Exception {
        return HTTP.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> send(String method, String path, JsonNode body, String token) throws Exception {
        return HTTP.send(request(path, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    public JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    public long taskCount() throws SQLException {
        try (var connection = database.connection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + database.schema() + ".notification_task")) {
            result.next();
            return result.getLong(1);
        }
    }

    public void sql(String sql) throws SQLException {
        try (var connection = database.connection(); var statement = connection.createStatement()) {
            connection.setSchema(database.schema());
            statement.execute(sql);
        }
    }

    @Override
    public void close() throws Exception {
        try { application.close(); } finally { database.close(); }
    }
}
