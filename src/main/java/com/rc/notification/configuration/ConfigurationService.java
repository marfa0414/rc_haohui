package com.rc.notification.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.api.ApiException;
import com.rc.notification.application.CanonicalDigest;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.application.RequestSnapshotFactory;
import com.rc.notification.application.Submission;
import com.rc.notification.application.SubmissionParser;
import com.rc.notification.application.TaskCursor;
import com.rc.notification.auth.ApiKeyAuthStrategy;
import com.rc.notification.auth.TenantSecretAccess;
import com.rc.notification.security.CallerPrincipal;
import com.rc.notification.security.OutboundPolicy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ConfigurationService {
    private static final Set<String> TEMPLATE_CONFIG = Set.of("method", "url", "headers", "body", "description", "examples");
    private static final Set<String> TEMPLATE_INPUT = Set.of("templateKey", "version", "ownerTenantId", "shared", "providerId",
            "endpointGroup", "method", "url", "headers", "body", "description", "examples", "expectedRevision", "copyFromId");
    private static final Set<String> AUTH_INPUT = Set.of("authProfileKey", "version", "tenantId", "type", "headerName", "prefix", "secretRef",
            "tokenUrl", "clientId", "clientSecretRef", "clientAuthenticationMethod", "scopes", "tokenLifetimeSeconds", "expectedRevision");
    private final ConfigurationRepository repository;
    private final RequestSnapshotFactory snapshots;
    private final OutboundPolicy outbound;
    private final TenantSecretAccess secrets;
    private final ObjectMapper json;
    private final ObjectMapper storedJson = StoredConfigurationJson.newMapper();
    private final TransactionTemplate transaction;
    public ConfigurationService(ConfigurationRepository repository, RequestSnapshotFactory snapshots, OutboundPolicy outbound,
                                ObjectMapper json, PlatformTransactionManager manager, TenantSecretAccess secrets) {
        this.repository = repository; this.snapshots = snapshots; this.outbound = outbound; this.json = json;
        this.secrets = secrets;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }
    public record Page(List<ObjectNode> items, String nextCursor) { }

    public ObjectNode createTemplate(ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, TEMPLATE_INPUT);
        return mutate(() -> {
            ObjectNode input = request.deepCopy();
            if (input.has("copyFromId")) {
                ObjectNode source = repository.template(uuid(input, "copyFromId"), false);
                requireTemplateRead(source, caller);
                ObjectNode merged = editable(source, TEMPLATE_INPUT);
                merged.setAll(input); input = merged;
            }
            templateIdentity(input, caller);
            repository.lockTemplateKey(input.path("templateKey").asText());
            List<ObjectNode> owners = repository.templateKey(input.path("templateKey").asText());
            if (!owners.isEmpty()) {
                ObjectNode owner = owners.get(0);
                requireTemplateWrite(owner, caller);
                if (!owner.path("ownerTenantId").equals(input.path("ownerTenantId"))
                        || owner.path("shared").asBoolean() != input.path("shared").asBoolean()) throw invalid();
            }
            ObjectNode created = repository.createTemplate(input, select(input, TEMPLATE_CONFIG), caller);
            repository.audit("template", null, created, "CREATE", caller);
            return created;
        });
    }

    public ObjectNode updateTemplate(UUID id, ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, TEMPLATE_INPUT); long expected = revision(request, false);
        return mutate(() -> {
            ObjectNode before = repository.template(id, true);
            requireTemplateWrite(before, caller); draft(before, expected);
            ObjectNode input = editable(before, TEMPLATE_INPUT); input.setAll(request);
            fixed(before, input, "templateKey", "version", "ownerTenantId", "shared");
            templateIdentity(input, caller);
            ObjectNode updated = repository.updateTemplate(id, input, select(input, TEMPLATE_CONFIG));
            repository.audit("template", before, updated, "UPDATE", caller);
            return updated;
        });
    }

    public ObjectNode template(UUID id, CallerPrincipal caller) {
        requireAdmin(caller); ObjectNode resource = repository.template(id, false); requireTemplateRead(resource, caller); return resource;
    }
    public ObjectNode validateTemplate(UUID id, CallerPrincipal caller) {
        ObjectNode resource = template(id, caller);
        snapshots.validate(select(resource, TEMPLATE_CONFIG));
        return json.createObjectNode().put("valid", true).put("revision", resource.path("revision").longValue());
    }
    public ObjectNode publishTemplate(UUID id, ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, Set.of("expectedRevision")); long expected = revision(request, false);
        return mutate(() -> {
            ObjectNode before = repository.template(id, true); requireTemplateWrite(before, caller); draft(before, expected);
            snapshots.validate(select(before, TEMPLATE_CONFIG));
            ObjectNode published = repository.publish(id, expected, true, caller);
            repository.audit("template", before, published, "PUBLISH", caller);
            return published;
        });
    }

    public ObjectNode preview(UUID id, ObjectNode request, CallerPrincipal caller) {
        ObjectNode resource = template(id, caller); keys(request, Set.of("eventId", "data"));
        String event = SubmissionParser.identifier(request.get("eventId"), 128);
        if (!request.path("data").isObject()) throw ApiException.invalid();
        ObjectNode document = json.createObjectNode().put("tenantId", "preview").put("operation", "preview").put("eventId", event);
        document.set("data", request.get("data"));
        JsonNode config = select(resource, TEMPLATE_CONFIG); snapshots.validate(config);
        RequestSnapshot snapshot = snapshots.create(config, new Submission("preview", "preview", event, document, new CanonicalDigest().digest(document)));
        ObjectNode result = json.createObjectNode().put("method", snapshot.method()).put("url", snapshot.url())
                .put("contentType", snapshot.contentType()).put("bodyBytes", snapshot.body().length);
        ObjectNode headers = result.putObject("headers"); snapshot.headers().forEach((name, value) -> headers.put(name, "***"));
        JsonNode body;
        try {
            if (snapshot.contentType().startsWith("application/json")) body = storedJson.readTree(snapshot.body());
            else {
                ObjectNode fields = json.createObjectNode();
                for (String part : new String(snapshot.body(), StandardCharsets.UTF_8).split("&")) {
                    if (part.isEmpty()) continue;
                    String[] pair = part.split("=", 2);
                    fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
                body = fields;
            }
        } catch (Exception malformed) { throw invalid(); }
        var fields = result.putArray("fields");
        result.set("bodyPreview", redact(body, "$", fields));
        return result;
    }
    private JsonNode redact(JsonNode value, String path, com.fasterxml.jackson.databind.node.ArrayNode fields) {
        if (value.isObject()) {
            ObjectNode masked = json.createObjectNode();
            value.fields().forEachRemaining(entry -> masked.set(entry.getKey(), redact(entry.getValue(), path + "." + entry.getKey(), fields)));
            return masked;
        }
        if (value.isArray()) {
            var masked = json.createArrayNode();
            for (int i = 0; i < value.size(); i++) masked.add(redact(value.get(i), path + "[" + i + "]", fields));
            return masked;
        }
        int length = value.isTextual() ? value.textValue().getBytes(StandardCharsets.UTF_8).length : value.toString().getBytes(StandardCharsets.UTF_8).length;
        fields.addObject().put("path", path).put("type", value.getNodeType().name().toLowerCase(Locale.ROOT)).put("length", length);
        return json.getNodeFactory().textNode("***");
    }

    public ObjectNode createAuth(ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, AUTH_INPUT); authIdentity(request, caller);
        return mutate(() -> {
            ObjectNode created = repository.createAuth(request, authParameters(request), secretReference(request), caller);
            repository.audit("auth_profile", null, created, "CREATE", caller); return created;
        });
    }
    public ObjectNode updateAuth(UUID id, ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, AUTH_INPUT); long expected = revision(request, false);
        return mutate(() -> {
            ObjectNode before = repository.auth(id, true); requireAuthAccess(before, caller); draft(before, expected);
            ObjectNode input = editable(before, AUTH_INPUT); input.setAll(request);
            fixed(before, input, "authProfileKey", "version", "tenantId"); authIdentity(input, caller);
            ObjectNode updated = repository.updateAuth(id, input, authParameters(input), secretReference(input));
            repository.audit("auth_profile", before, updated, "UPDATE", caller); return updated;
        });
    }
    public ObjectNode auth(UUID id, CallerPrincipal caller) {
        requireAdmin(caller); ObjectNode resource = repository.auth(id, false); requireAuthAccess(resource, caller); return resource;
    }
    public ObjectNode publishAuth(UUID id, ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); keys(request, Set.of("expectedRevision")); long expected = revision(request, false);
        return mutate(() -> {
            ObjectNode before = repository.auth(id, true); requireAuthAccess(before, caller); draft(before, expected); validateAuth(before);
            ObjectNode published = repository.publish(id, expected, false, caller);
            repository.audit("auth_profile", before, published, "PUBLISH", caller); return published;
        });
    }

    public ObjectNode switchRoute(String tenant, String operation, ObjectNode request, CallerPrincipal caller) {
        requireAdmin(caller); routePermission(tenant, operation, caller); keys(request, Set.of("expectedRevision", "templateVersionId", "authProfileId"));
        long expected = revision(request, true); UUID templateId = uuid(request, "templateVersionId"), authId = uuid(request, "authProfileId");
        return mutate(() -> {
            ObjectNode template = repository.template(templateId, true); ObjectNode auth = repository.auth(authId, true);
            if (!(template.path("shared").asBoolean() || tenant.equals(template.path("ownerTenantId").asText()))
                    || !tenant.equals(auth.path("tenantId").asText())) throw forbidden();
            if (!"published".equals(template.path("status").asText()) || !"published".equals(auth.path("status").asText())) throw invalid();
            snapshots.validate(select(template, TEMPLATE_CONFIG)); validateAuth(auth);
            if ("api_key".equals(auth.path("type").asText())) {
                String name = auth.path("headerName").asText();
                var names = template.path("headers").fieldNames();
                while (names.hasNext()) if (name.equalsIgnoreCase(names.next())) throw invalid();
            }
            ObjectNode before = repository.route(tenant, operation, true);
            ObjectNode updated = repository.switchRoute(tenant, operation, templateId, authId, expected, caller);
            repository.audit("route", before, updated, "BIND", caller); return updated;
        });
    }
    public ObjectNode route(String tenant, String operation, CallerPrincipal caller) {
        requireAdmin(caller); SubmissionParser.identifier(tenant, 64); SubmissionParser.identifier(operation, 128);
        if (!caller.allowsTenant(tenant) || !caller.allowsOperation(operation)) throw ConfigurationRepository.notFound();
        ObjectNode route = repository.route(tenant, operation, false);
        if (route == null) throw ConfigurationRepository.notFound(); return route;
    }

    public Page list(boolean templates, String tenant, String cursor, int limit, CallerPrincipal caller) {
        requireAdmin(caller); listPermission(tenant, limit, caller);
        List<ObjectNode> rows = repository.list(templates, caller, tenant, TaskCursor.parse(cursor), limit + 1);
        boolean more = rows.size() > limit; List<ObjectNode> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        ObjectNode last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new Page(items, more ? new TaskCursor(Instant.parse(last.path("createdAt").asText()), UUID.fromString(last.path("id").asText())).encode() : null);
    }
    public Page routes(String tenant, String cursor, int limit, CallerPrincipal caller) {
        requireAdmin(caller); listPermission(tenant, limit, caller);
        String cursorTenant = null, cursorOperation = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 512) throw ApiException.invalid();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2) throw ApiException.invalid();
                cursorTenant = SubmissionParser.identifier(parts[0], 64); cursorOperation = SubmissionParser.identifier(parts[1], 128);
            } catch (RuntimeException invalidCursor) { throw ApiException.invalid(); }
        }
        List<ObjectNode> rows = repository.routes(caller, tenant, cursorTenant, cursorOperation, limit + 1);
        boolean more = rows.size() > limit; List<ObjectNode> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        ObjectNode last = items.isEmpty() ? null : items.get(items.size() - 1);
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString((last.path("tenantId").asText() + "|" + last.path("operation").asText()).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(items, next);
    }

    private void templateIdentity(ObjectNode input, CallerPrincipal caller) {
        SubmissionParser.identifier(input.get("templateKey"), 128); positiveVersion(input);
        SubmissionParser.identifier(input.get("providerId"), 128); SubmissionParser.identifier(input.get("endpointGroup"), 128);
        if (input.has("shared") && !input.get("shared").isBoolean()) throw invalid();
        if (!input.has("shared")) input.put("shared", false);
        if (input.path("shared").asBoolean()) {
            if (!caller.roles().contains("ADMIN")) throw forbidden();
            if (input.hasNonNull("ownerTenantId")) SubmissionParser.identifier(input.get("ownerTenantId"), 64); else input.putNull("ownerTenantId");
        } else {
            String tenant = SubmissionParser.identifier(input.get("ownerTenantId"), 64);
            if (!caller.allowsTenant(tenant)) throw forbidden();
        }
    }
    private void authIdentity(ObjectNode input, CallerPrincipal caller) {
        String tenant = SubmissionParser.identifier(input.get("tenantId"), 64);
        if (!caller.allowsTenant(tenant)) throw forbidden();
        SubmissionParser.identifier(input.get("authProfileKey"), 128); positiveVersion(input);
        if (!Set.of("api_key", "oauth2_client_credentials").contains(input.path("type").asText())) throw invalid();
        permittedSecretReference(input);
    }
    private String secretReference(JsonNode input) {
        String field = "api_key".equals(input.path("type").asText()) ? "secretRef" : "clientSecretRef";
        String value = input.path(field).asText("");
        if (!value.matches("env:[A-Za-z_][A-Za-z0-9_]*") || value.length() > 256) throw invalid(); return value;
    }
    private void permittedSecretReference(JsonNode input) {
        String reference = secretReference(input);
        try { secrets.requireAllowed(input.path("tenantId").asText(), reference); }
        catch (TenantSecretAccess.ForbiddenReference forbiddenReference) {
            throw new ApiException(403, "SECRET_REFERENCE_FORBIDDEN", "Secret reference is not permitted for this tenant");
        }
    }
    private ObjectNode authParameters(ObjectNode input) {
        if ("api_key".equals(input.path("type").asText())) return select(input, Set.of("headerName", "prefix"));
        return select(input, Set.of("tokenUrl", "clientId", "clientAuthenticationMethod", "scopes", "tokenLifetimeSeconds"));
    }
    private void validateAuth(ObjectNode input) {
        permittedSecretReference(input);
        if ("api_key".equals(input.path("type").asText())) {
            try { ApiKeyAuthStrategy.validateParameters(input); }
            catch (IllegalArgumentException invalidHeader) { throw invalid(); }
        } else if ("oauth2_client_credentials".equals(input.path("type").asText())) {
            outbound.validateConfigurationUrl(text(input, "tokenUrl")); text(input, "clientId");
            if (!Set.of("client_secret_basic", "client_secret_post").contains(input.path("clientAuthenticationMethod").asText())) throw invalid();
            if (input.has("scopes")) {
                if (!input.get("scopes").isArray()) throw invalid();
                for (JsonNode scope : input.get("scopes")) if (!scope.isTextual() || !scope.textValue().matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")) throw invalid();
            }
            if (input.has("tokenLifetimeSeconds") && (!input.get("tokenLifetimeSeconds").isIntegralNumber()
                    || !input.get("tokenLifetimeSeconds").canConvertToLong() || input.get("tokenLifetimeSeconds").longValue() <= 0
                    || input.get("tokenLifetimeSeconds").longValue() > Long.MAX_VALUE / 1_000_000_000L)) throw invalid();
        } else throw invalid();
    }

    private void requireAdmin(CallerPrincipal caller) { if (!caller.hasRole("CONFIG_ADMIN")) throw forbidden(); }
    private void requireTemplateRead(ObjectNode resource, CallerPrincipal caller) {
        if (!resource.path("shared").asBoolean() && !caller.allowsTenant(resource.path("ownerTenantId").asText())) throw ConfigurationRepository.notFound();
    }
    private void requireTemplateWrite(ObjectNode resource, CallerPrincipal caller) {
        if (resource.path("shared").asBoolean() ? !caller.roles().contains("ADMIN") : !caller.allowsTenant(resource.path("ownerTenantId").asText())) {
            throw ConfigurationRepository.notFound();
        }
    }
    private void requireAuthAccess(ObjectNode resource, CallerPrincipal caller) {
        if (!caller.allowsTenant(resource.path("tenantId").asText())) throw ConfigurationRepository.notFound();
    }
    private void routePermission(String tenant, String operation, CallerPrincipal caller) {
        SubmissionParser.identifier(tenant, 64); SubmissionParser.identifier(operation, 128);
        if (!caller.allowsTenant(tenant) || !caller.allowsOperation(operation)) throw forbidden();
    }
    private void listPermission(String tenant, int limit, CallerPrincipal caller) {
        if (limit < 1 || limit > 100) throw ApiException.invalid();
        if (tenant != null) { SubmissionParser.identifier(tenant, 64); if (!caller.allowsTenant(tenant)) throw forbidden(); }
    }
    private void draft(ObjectNode resource, long revision) {
        if (!"draft".equals(resource.path("status").asText()) || resource.path("revision").longValue() != revision) throw ConfigurationRepository.conflict();
    }
    private long revision(JsonNode input, boolean zero) {
        JsonNode value = input.path("expectedRevision");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < (zero ? 0 : 1)) throw ApiException.invalid(); return value.longValue();
    }
    private void positiveVersion(JsonNode input) {
        JsonNode version = input.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() <= 0) throw invalid();
    }
    private void fixed(JsonNode before, JsonNode after, String... fields) {
        for (String field : fields) {
            JsonNode a = before.path(field), b = after.path(field);
            if (a.isNumber() && b.isNumber()) {
                if (a.decimalValue().compareTo(b.decimalValue()) != 0) throw invalid();
            } else if (!a.equals(b)) throw invalid();
        }
    }
    private ObjectNode select(JsonNode input, Set<String> keys) {
        ObjectNode result = json.createObjectNode(); for (String key : keys) if (input.has(key)) result.set(key, input.get(key)); return result;
    }
    private ObjectNode editable(ObjectNode input, Set<String> keys) { return select(input, keys); }
    private void keys(ObjectNode input, Set<String> allowed) {
        input.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
    }
    private UUID uuid(JsonNode input, String field) {
        try { return UUID.fromString(text(input, field)); } catch (RuntimeException failure) { throw ApiException.invalid(); }
    }
    private String text(JsonNode input, String field) {
        JsonNode value = input.path(field); if (!value.isTextual() || value.textValue().isEmpty()) throw invalid(); return value.textValue();
    }
    private ObjectNode mutate(Supplier<ObjectNode> operation) {
        try { return transaction.execute(ignored -> operation.get()); }
        catch (DuplicateKeyException duplicate) { throw ConfigurationRepository.conflict(); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_CONFIGURATION", "Configuration contains an invalid field or combination"); }
    private static ApiException forbidden() { return new ApiException(403, "FORBIDDEN_OPERATION", "Configuration access is not authorized"); }
}
