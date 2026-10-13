package com.rc.notification.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import com.rc.notification.configuration.StoredConfigurationJson;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.application.DeliveryProperties;
import com.rc.notification.application.Submission;
import com.rc.notification.application.TaskCursor;
import com.rc.notification.application.TaskView;
import com.rc.notification.security.CallerPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private static final ObjectMapper CONFIG_JSON = StoredConfigurationJson.newMapper();
    private static final String VIEW_COLUMNS = """
            id, tenant_id, operation, event_id, status, run_number, run_attempt_count,
            total_attempt_count, next_attempt_at, deadline_at, last_outcome, last_error_code,
            last_http_status, template_version_id, auth_profile_id, route_revision, created_at, updated_at, defer_reason
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final DeliveryProperties defaults;
    public NotificationRepository(JdbcTemplate jdbc, DeliveryProperties defaults) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.defaults = defaults;
    }

    public record Existing(TaskView view, byte[] digest) { }
    public record Route(UUID templateId, UUID authProfileId, long revision, String providerId,
                        String endpointGroup, JsonNode configuration) { }

    public Optional<Existing> findEvent(Submission submission) {
        return jdbc.query("SELECT " + VIEW_COLUMNS + ", request_hash FROM notification_task WHERE tenant_id=? AND operation=? AND event_id=?",
                (row, number) -> new Existing(view(row), row.getBytes("request_hash")),
                submission.tenantId(), submission.operation(), submission.eventId()).stream().findFirst();
    }

    public Route route(Submission submission) {
        List<Route> routes = jdbc.query("""
                SELECT r.template_version_id, r.auth_profile_id, r.revision,
                       t.provider_id, t.endpoint_group, t.config
                FROM notification_route r
                JOIN notification_template_version t ON t.id=r.template_version_id
                JOIN auth_profile a ON a.id=r.auth_profile_id AND a.tenant_id=r.tenant_id
                WHERE r.tenant_id=? AND r.operation=? AND t.status='published' AND a.status='published'
                  AND (t.shared OR t.owner_tenant_id=r.tenant_id)
                """, (row, number) -> new Route(row.getObject("template_version_id", UUID.class),
                row.getObject("auth_profile_id", UUID.class), row.getLong("revision"),
                row.getString("provider_id"), row.getString("endpoint_group"), config(row.getString("config"))),
                submission.tenantId(), submission.operation());
        if (routes.isEmpty()) throw ApiException.routeUnavailable();
        return routes.get(0);
    }

    public Optional<UUID> insert(Submission submission, Route route, RequestSnapshot snapshot) {
        UUID id = UUID.randomUUID();
        String headers;
        try {
            headers = CONFIG_JSON.writeValueAsString(snapshot.headers());
        } catch (JsonProcessingException invalidConfiguration) {
            throw ApiException.routeUnavailable();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id).addValue("tenant", submission.tenantId()).addValue("operation", submission.operation())
                .addValue("event", submission.eventId()).addValue("hash", submission.digest())
                .addValue("template", route.templateId()).addValue("auth", route.authProfileId()).addValue("revision", route.revision())
                .addValue("provider", route.providerId()).addValue("endpoint", route.endpointGroup())
                .addValue("quota", submission.tenantId() + ":" + route.authProfileId())
                .addValue("url", snapshot.url()).addValue("method", snapshot.method()).addValue("headers", headers)
                .addValue("contentType", snapshot.contentType()).addValue("body", snapshot.body())
                .addValue("maxAttempts", defaults.maxAttempts()).addValue("runMillis", defaults.runDuration().toMillis());
        return named.query("""
                WITH accepted AS MATERIALIZED (SELECT clock_timestamp() AS at)
                INSERT INTO notification_task(id, tenant_id, operation, event_id, request_hash,
                    template_version_id, auth_profile_id, route_revision, provider_id, endpoint_group, quota_key,
                    request_url, request_method, ordinary_headers, content_type, body_bytes,
                    status, max_attempts, run_started_at, deadline_at, next_attempt_at, created_at, updated_at)
                SELECT :id, :tenant, :operation, :event, :hash, :template, :auth, :revision, :provider, :endpoint,
                    :quota, :url, :method, CAST(:headers AS jsonb), :contentType, :body,
                    'pending', :maxAttempts, at, at + :runMillis * interval '1 millisecond', at, at, at FROM accepted
                ON CONFLICT (tenant_id, operation, event_id) DO NOTHING RETURNING id
                """, parameters, (row, number) -> row.getObject("id", UUID.class)).stream().findFirst();
    }

    public Optional<TaskView> find(UUID id, CallerPrincipal caller) {
        return find(id, caller, false);
    }

    public Optional<TaskView> findForUpdate(UUID id, CallerPrincipal caller) {
        return find(id, caller, true);
    }

    private Optional<TaskView> find(UUID id, CallerPrincipal caller, boolean lock) {
        if (caller.tenants().isEmpty() || caller.operations().isEmpty()) return Optional.empty();
        StringBuilder sql = new StringBuilder("SELECT ").append(VIEW_COLUMNS)
                .append(" FROM notification_task WHERE id=:id");
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id);
        if (!caller.tenants().contains("*")) {
            sql.append(" AND tenant_id IN (:tenants)");
            parameters.addValue("tenants", caller.tenants());
        }
        if (!caller.operations().contains("*")) {
            sql.append(" AND operation IN (:operations)");
            parameters.addValue("operations", caller.operations());
        }
        if (lock) sql.append(" FOR UPDATE");
        return named.query(sql.toString(), parameters, VIEW_MAPPER).stream().findFirst();
    }

    public List<TaskView> list(String tenant, String status, TaskCursor cursor, int count, CallerPrincipal caller) {
        if (!caller.operations().contains("*") && caller.operations().isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT ").append(VIEW_COLUMNS)
                .append(" FROM notification_task WHERE tenant_id=:tenant");
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenant).addValue("count", count);
        if (!caller.operations().contains("*")) {
            sql.append(" AND operation IN (:operations)");
            parameters.addValue("operations", caller.operations());
        }
        if (status != null) {
            sql.append(" AND status=:status");
            parameters.addValue("status", status);
        }
        if (cursor != null) {
            sql.append(" AND (created_at,id)<(:createdAt,:cursorId)");
            parameters.addValue("createdAt", Timestamp.from(cursor.createdAt())).addValue("cursorId", cursor.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :count");
        return named.query(sql.toString(), parameters, VIEW_MAPPER);
    }

    private static JsonNode config(String json) {
        try { return CONFIG_JSON.readTree(json); }
        catch (JsonProcessingException invalid) { throw ApiException.routeUnavailable(); }
    }

    private static final RowMapper<TaskView> VIEW_MAPPER = (row, number) -> view(row);
    private static TaskView view(ResultSet row) throws SQLException {
        return new TaskView(row.getObject("id", UUID.class), row.getString("tenant_id"), row.getString("operation"),
                row.getString("event_id"), row.getString("status"), row.getInt("run_number"),
                row.getInt("run_attempt_count"), row.getLong("total_attempt_count"), instant(row, "next_attempt_at"),
                instant(row, "deadline_at"), row.getString("last_outcome"), row.getString("last_error_code"),
                row.getObject("last_http_status", Integer.class), row.getObject("template_version_id", UUID.class),
                row.getObject("auth_profile_id", UUID.class), row.getLong("route_revision"),
                instant(row, "created_at"), instant(row, "updated_at"), row.getString("defer_reason"));
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
