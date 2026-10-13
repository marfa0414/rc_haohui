package com.rc.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** CP02 published-fixture subset; field lookup and encoder strategies are added in CP03. */
@Component
public class ConstantSnapshotFactory implements RequestSnapshotFactory {
    private static final Set<String> RESERVED = Set.of("authorization", "host", "content-length", "connection",
            "transfer-encoding", "content-type", "proxy-authorization", "te", "trailer", "upgrade");
    private final ObjectMapper mapper;
    public ConstantSnapshotFactory(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public RequestSnapshot create(JsonNode template, Submission submission) {
        try {
            String method = template.path("method").textValue();
            String url = template.path("url").textValue();
            if (method == null || !Set.of("POST", "PUT", "PATCH").contains(method) || url == null) throw ApiException.routeUnavailable();
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null) throw ApiException.routeUnavailable();
            JsonNode body = template.path("body");
            if (!"json".equals(body.path("format").asText()) || !body.path("fields").isObject()) throw ApiException.routeUnavailable();
            var rendered = mapper.createObjectNode();
            var fields = body.path("fields").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode rule = entry.getValue();
                if (!rule.isObject() || !rule.has("constant") || rule.has("from") || rule.has("fields")) throw ApiException.routeUnavailable();
                rendered.set(entry.getKey(), rule.get("constant"));
            }
            byte[] bytes = mapper.writeValueAsBytes(rendered);
            if (bytes.length > SubmissionParser.MAX_BYTES) throw ApiException.routeUnavailable();
            Map<String, String> headers = new HashMap<>();
            Set<String> names = new HashSet<>();
            JsonNode configuredHeaders = template.path("headers");
            if (!configuredHeaders.isMissingNode() && !configuredHeaders.isObject()) throw ApiException.routeUnavailable();
            var entries = configuredHeaders.fields();
            int headerBytes = 0;
            while (entries.hasNext()) {
                var entry = entries.next();
                String name = entry.getKey();
                JsonNode rule = entry.getValue();
                if (!rule.isObject() || !rule.path("constant").isTextual() || rule.has("from")) throw ApiException.routeUnavailable();
                String value = rule.path("constant").textValue();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !names.add(lower) || RESERVED.contains(lower)
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw ApiException.routeUnavailable();
                headerBytes += name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length + 4;
                headers.put(name, value);
            }
            if (headerBytes > 16384) throw ApiException.routeUnavailable();
            return new RequestSnapshot(url, method, headers, "application/json", bytes);
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception invalidConfiguration) {
            throw ApiException.routeUnavailable();
        }
    }
}
