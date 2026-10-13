package com.rc.notification.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.api.ApiException;
import com.rc.notification.application.TaskCursor;
import com.rc.notification.security.CallerPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigurationRepository {
    private static final ObjectMapper JSON = StoredConfigurationJson.newMapper();
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public ConfigurationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; named = new NamedParameterJdbcTemplate(jdbc); }

    public ObjectNode template(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM notification_template_version WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (row, number) -> template(row), id).stream().findFirst().orElseThrow(ConfigurationRepository::notFound);
    }
    public ObjectNode auth(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM auth_profile WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (row, number) -> auth(row), id).stream().findFirst().orElseThrow(ConfigurationRepository::notFound);
    }
    public ObjectNode route(String tenant, String operation, boolean lock) {
        return jdbc.query("SELECT * FROM notification_route WHERE tenant_id=? AND operation=?" + (lock ? " FOR UPDATE" : ""),
                (row, number) -> route(row), tenant, operation).stream().findFirst().orElse(null);
    }
    public List<ObjectNode> templateKey(String key) {
        return jdbc.query("SELECT * FROM notification_template_version WHERE template_key=? LIMIT 1",
                (row, number) -> template(row), key);
    }
    public void lockTemplateKey(String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "template-key:" + key);
    }

    public ObjectNode createTemplate(ObjectNode input, ObjectNode config, CallerPrincipal caller) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_template_version(id,template_key,version,owner_tenant_id,shared,
                    provider_id,endpoint_group,config,created_by) VALUES (?,?,?,?,?,?,?,?::jsonb,?)
                """, id, input.path("templateKey").asText(), input.path("version").intValue(),
                nullableText(input, "ownerTenantId"), input.path("shared").asBoolean(), input.path("providerId").asText(),
                input.path("endpointGroup").asText(), StoredConfigurationJson.forStorage(config), caller.id());
        return template(id, false);
    }
    public ObjectNode updateTemplate(UUID id, ObjectNode input, ObjectNode config) {
        int changed = jdbc.update("""
                UPDATE notification_template_version SET provider_id=?,endpoint_group=?,config=?::jsonb,revision=revision+1
                WHERE id=? AND revision=? AND status='draft'
                """, input.path("providerId").asText(), input.path("endpointGroup").asText(), StoredConfigurationJson.forStorage(config),
                id, input.path("expectedRevision").longValue());
        if (changed != 1) throw conflict();
        return template(id, false);
    }
    public ObjectNode createAuth(ObjectNode input, ObjectNode parameters, String secretReference, CallerPrincipal caller) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_profile(id,tenant_id,profile_key,version,auth_type,parameters,secret_ref,created_by)
                VALUES (?,?,?,?,?,?::jsonb,?,?)
                """, id, input.path("tenantId").asText(), input.path("authProfileKey").asText(),
                input.path("version").intValue(), input.path("type").asText(), StoredConfigurationJson.forStorage(parameters), secretReference, caller.id());
        return auth(id, false);
    }
    public ObjectNode updateAuth(UUID id, ObjectNode input, ObjectNode parameters, String secretReference) {
        int changed = jdbc.update("""
                UPDATE auth_profile SET auth_type=?,parameters=?::jsonb,secret_ref=?,revision=revision+1
                WHERE id=? AND revision=? AND status='draft'
                """, input.path("type").asText(), StoredConfigurationJson.forStorage(parameters), secretReference,
                id, input.path("expectedRevision").longValue());
        if (changed != 1) throw conflict();
        return auth(id, false);
    }
    public ObjectNode publish(UUID id, long revision, boolean template, CallerPrincipal caller) {
        String table = template ? "notification_template_version" : "auth_profile";
        int changed = jdbc.update("UPDATE " + table + " SET status='published',published_by=?,published_at=clock_timestamp(),revision=revision+1"
                + " WHERE id=? AND revision=? AND status='draft'", caller.id(), id, revision);
        if (changed != 1) throw conflict();
        return template ? template(id, false) : auth(id, false);
    }

    public ObjectNode switchRoute(String tenant, String operation, UUID template, UUID auth, long expected, CallerPrincipal caller) {
        int changed;
        if (expected == 0) {
            changed = jdbc.update("""
                    INSERT INTO notification_route(tenant_id,operation,template_version_id,auth_profile_id,updated_by)
                    VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,operation) DO NOTHING
                    """, tenant, operation, template, auth, caller.id());
        } else {
            changed = jdbc.update("""
                    UPDATE notification_route SET template_version_id=?,auth_profile_id=?,revision=revision+1,
                        updated_by=?,updated_at=clock_timestamp() WHERE tenant_id=? AND operation=? AND revision=?
                    """, template, auth, caller.id(), tenant, operation, expected);
        }
        if (changed != 1) throw conflict();
        return route(tenant, operation, false);
    }

    public List<ObjectNode> list(boolean templates, CallerPrincipal caller, String tenant, TaskCursor cursor, int count) {
        String table = templates ? "notification_template_version" : "auth_profile";
        String owner = templates ? "owner_tenant_id" : "tenant_id";
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table).append(" WHERE true");
        MapSqlParameterSource parameters = new MapSqlParameterSource("count", count);
        if (tenant != null) {
            sql.append(" AND (").append(owner).append("=:tenant").append(templates ? " OR shared)" : ")");
            parameters.addValue("tenant", tenant);
        } else if (!caller.tenants().contains("*")) {
            if (caller.tenants().isEmpty()) {
                sql.append(templates ? " AND shared" : " AND false");
            } else {
                sql.append(" AND (").append(owner).append(" IN (:tenants)").append(templates ? " OR shared)" : ")");
                parameters.addValue("tenants", caller.tenants());
            }
        }
        if (cursor != null) {
            sql.append(" AND (created_at,id)<(:createdAt,:cursorId)");
            parameters.addValue("createdAt", Timestamp.from(cursor.createdAt())).addValue("cursorId", cursor.id());
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT :count");
        return named.query(sql.toString(), parameters, (row, number) -> templates ? template(row) : auth(row));
    }

    public List<ObjectNode> routes(CallerPrincipal caller, String tenant, String cursorTenant, String cursorOperation, int count) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notification_route WHERE true");
        MapSqlParameterSource parameters = new MapSqlParameterSource("count", count);
        if (tenant != null) { sql.append(" AND tenant_id=:tenant"); parameters.addValue("tenant", tenant); }
        else if (!caller.tenants().contains("*")) {
            if (caller.tenants().isEmpty()) sql.append(" AND false");
            else { sql.append(" AND tenant_id IN (:tenants)"); parameters.addValue("tenants", caller.tenants()); }
        }
        if (!caller.operations().contains("*")) {
            if (caller.operations().isEmpty()) sql.append(" AND false");
            else { sql.append(" AND operation IN (:operations)"); parameters.addValue("operations", caller.operations()); }
        }
        if (cursorTenant != null) {
            sql.append(" AND (tenant_id,operation)>(:cursorTenant,:cursorOperation)");
            parameters.addValue("cursorTenant", cursorTenant).addValue("cursorOperation", cursorOperation);
        }
        sql.append(" ORDER BY tenant_id,operation LIMIT :count");
        return named.query(sql.toString(), parameters, (row, number) -> route(row));
    }

    public void audit(String type, ObjectNode before, ObjectNode after, String action, CallerPrincipal caller) {
        String id = after.has("id") ? after.path("id").asText() : after.path("tenantId").asText() + ":" + after.path("operation").asText();
        String tenant = nullableText(after, after.has("tenantId") ? "tenantId" : "ownerTenantId");
        jdbc.update("""
                INSERT INTO audit_event(id,actor_id,tenant_id,object_type,object_id,action,before_summary,after_summary)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)
                """, UUID.randomUUID(), caller.id(), tenant, type, id, action,
                before == null ? null : summary(before).toString(), summary(after).toString());
    }

    private ObjectNode summary(ObjectNode object) {
        ObjectNode summary = JSON.createObjectNode();
        for (String key : List.of("revision", "status", "templateVersionId", "authProfileId")) {
            if (object.has(key)) summary.set(key, object.get(key));
        }
        return summary;
    }
    private ObjectNode template(ResultSet row) throws SQLException {
        ObjectNode node = object(row.getString("config"));
        common(node, row);
        node.put("templateKey", row.getString("template_key")).put("version", row.getInt("version"))
                .put("ownerTenantId", row.getString("owner_tenant_id")).put("shared", row.getBoolean("shared"))
                .put("providerId", row.getString("provider_id")).put("endpointGroup", row.getString("endpoint_group"));
        return node;
    }
    private ObjectNode auth(ResultSet row) throws SQLException {
        ObjectNode node = object(row.getString("parameters"));
        common(node, row);
        String type = row.getString("auth_type");
        node.put("authProfileKey", row.getString("profile_key")).put("version", row.getInt("version"))
                .put("tenantId", row.getString("tenant_id")).put("type", type)
                .put(type.equals("api_key") ? "secretRef" : "clientSecretRef", row.getString("secret_ref"));
        return node;
    }
    private ObjectNode route(ResultSet row) throws SQLException {
        return JSON.createObjectNode().put("tenantId", row.getString("tenant_id")).put("operation", row.getString("operation"))
                .put("templateVersionId", row.getString("template_version_id")).put("authProfileId", row.getString("auth_profile_id"))
                .put("revision", row.getLong("revision")).put("updatedBy", row.getString("updated_by"))
                .put("updatedAt", row.getTimestamp("updated_at").toInstant().toString());
    }
    private void common(ObjectNode node, ResultSet row) throws SQLException {
        node.put("id", row.getString("id")).put("revision", row.getLong("revision")).put("status", row.getString("status"))
                .put("createdBy", row.getString("created_by")).put("createdAt", row.getTimestamp("created_at").toInstant().toString())
                .put("publishedBy", row.getString("published_by"));
        Timestamp at = row.getTimestamp("published_at");
        if (at == null) node.putNull("publishedAt"); else node.put("publishedAt", at.toInstant().toString());
    }
    private ObjectNode object(String value) {
        try { return (ObjectNode) JSON.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("Stored configuration is invalid"); }
    }
    static String nullableText(JsonNode node, String key) { return node.path(key).isTextual() ? node.path(key).textValue() : null; }
    static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "Configuration was not found"); }
    static ApiException conflict() { return new ApiException(409, "CONFIG_CONFLICT", "Configuration revision or state has changed"); }
}
