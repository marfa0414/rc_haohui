package com.rc.notification.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.application.RequestSnapshot;
import com.rc.notification.application.Submission;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deliberately finite mapping grammar: object paths, constants and nested output fields. */
public final class RequestMapper {
    private static final Set<String> TEMPLATE_KEYS = Set.of("method", "url", "headers", "body", "description", "examples");
    private static final Set<String> RULE_KEYS = Set.of("from", "constant", "fields", "type", "required");
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array");
    private static final Set<String> RESERVED = Set.of("authorization", "host", "content-length", "connection",
            "transfer-encoding", "content-type", "proxy-authorization", "proxy-connection", "keep-alive", "te", "trailer", "upgrade", "expect");
    private static final int MAX_DEPTH = 64;
    private final ObjectMapper mapper;
    private final Map<String, BodyEncoder> encoders;

    public RequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
        Map<String, BodyEncoder> registered = new LinkedHashMap<>();
        for (BodyEncoder encoder : List.of(new JsonBodyEncoder(mapper), new FormBodyEncoder())) {
            registered.put(encoder.format(), encoder);
        }
        encoders = Map.copyOf(registered);
    }

    public void validate(JsonNode template) {
        keys(template, TEMPLATE_KEYS, "template");
        if (!template.path("method").isTextual() || !Set.of("POST", "PUT", "PATCH").contains(template.path("method").textValue())
                || !template.path("url").isTextual()) throw MappingFailure.configuration("method/url");
        if (template.has("description") && !template.path("description").isTextual()) throw MappingFailure.configuration("description");
        if (template.has("examples") && !(template.path("examples").isArray() || template.path("examples").isObject())) {
            throw MappingFailure.configuration("examples");
        }
        JsonNode body = template.path("body");
        keys(body, Set.of("format", "fields"), "body");
        String format = body.path("format").textValue();
        BodyEncoder encoder = format == null ? null : encoders.get(format);
        if (encoder == null) throw MappingFailure.configuration("body.format");
        boolean form = encoder.format().equals("form");
        validateFields(body.path("fields"), form, 0);
        JsonNode headers = template.path("headers");
        if (!headers.isMissingNode()) {
            if (!headers.isObject()) throw MappingFailure.configuration("headers");
            Set<String> seen = new HashSet<>();
            var entries = headers.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                String name = entry.getKey();
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || !seen.add(name.toLowerCase(Locale.ROOT)) || RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
                    throw MappingFailure.configuration("headers.name");
                }
                validateRule(entry.getValue(), false, true, 0);
            }
        }
        // Reject statically oversized constant output before publication. Dynamic values are checked at acceptance.
        encoder.encode(renderFields(body.path("fields"), null, true));
        renderHeaders(headers, null, true);
    }

    public RequestSnapshot render(JsonNode template, Submission submission) {
        JsonNode body = template.path("body");
        BodyEncoder encoder = encoders.get(body.path("format").textValue());
        ObjectNode fields = renderFields(body.path("fields"), submission.document(), false);
        Map<String, String> headers = renderHeaders(template.path("headers"), submission.document(), false);
        byte[] bytes = encoder.encode(fields);
        return new RequestSnapshot(template.path("url").textValue(), template.path("method").textValue(),
                headers, encoder.contentType(), bytes);
    }

    private void validateFields(JsonNode fields, boolean form, int depth) {
        if (depth > MAX_DEPTH || !fields.isObject()) throw MappingFailure.configuration("body.fields");
        var entries = fields.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            unicode(entry.getKey());
            validateRule(entry.getValue(), form, false, depth + 1);
        }
    }

    private void validateRule(JsonNode rule, boolean form, boolean header, int depth) {
        if (depth > MAX_DEPTH) throw MappingFailure.configuration("fields.depth");
        keys(rule, RULE_KEYS, "field.rule");
        if (rule.has("required") && !rule.path("required").isBoolean()) throw MappingFailure.configuration("field.required");
        String type = rule.has("type") ? rule.path("type").textValue() : null;
        if (rule.has("type") && (type == null || !TYPES.contains(type))) throw MappingFailure.configuration("field.type");
        int alternatives = (rule.has("from") ? 1 : 0) + (rule.has("constant") ? 1 : 0) + (rule.has("fields") ? 1 : 0);
        if (alternatives != 1) throw MappingFailure.configuration("field.source");
        if (rule.has("fields")) {
            if (form || header || type != null && !type.equals("object")) throw MappingFailure.configuration("field.fields");
            validateFields(rule.path("fields"), false, depth);
            return;
        }
        if (header && type != null && !type.equals("string")) throw MappingFailure.configuration("headers.type");
        if (form && type != null && Set.of("object", "array").contains(type)) throw MappingFailure.configuration("body.form.type");
        if (rule.has("from")) {
            String from = rule.path("from").textValue();
            validatePath(from);
        } else {
            JsonNode value = rule.get("constant");
            validateConstant(value, 0);
            if (rule.path("required").asBoolean(false) && value.isNull() || !matches(value, type)) {
                throw MappingFailure.configuration("field.constant");
            }
            if (header && (!value.isTextual() || hasForbiddenHeaderCharacter(value.textValue()))) throw MappingFailure.configuration("headers.constant");
            if (form && (!FormBodyEncoder.scalar(value) || value.isTextual() && value.textValue().isEmpty())) {
                throw MappingFailure.configuration("body.form.constant");
            }
        }
    }

    private ObjectNode renderFields(JsonNode definitions, JsonNode document, boolean constantsOnly) {
        ObjectNode output = mapper.createObjectNode();
        var entries = definitions.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode value = renderRule(entry.getValue(), document, constantsOnly);
            if (value != null) output.set(entry.getKey(), value);
        }
        return output;
    }

    private JsonNode renderRule(JsonNode rule, JsonNode document, boolean constantsOnly) {
        if (rule.has("fields")) return renderFields(rule.path("fields"), document, constantsOnly);
        if (rule.has("constant")) return rule.get("constant");
        if (constantsOnly) return null;
        JsonNode current = document;
        for (String segment : rule.path("from").textValue().split("\\.")) {
            if (current == null || !current.isObject()) {
                current = null;
                break;
            }
            current = current.get(segment);
        }
        if (current == null || current.isNull()) {
            if (rule.path("required").asBoolean(false)) throw MappingFailure.input();
            return null;
        }
        if (!matches(current, rule.has("type") ? rule.path("type").textValue() : null)) throw MappingFailure.input();
        return current;
    }

    private Map<String, String> renderHeaders(JsonNode definitions, JsonNode document, boolean constantsOnly) {
        Map<String, String> output = new LinkedHashMap<>();
        long bytes = 0;
        var entries = definitions.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode value = renderRule(entry.getValue(), document, constantsOnly);
            if (value == null) continue;
            if (!value.isTextual() || hasForbiddenHeaderCharacter(value.textValue())) throw MappingFailure.input();
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length + (long) value.textValue().getBytes(StandardCharsets.UTF_8).length + 4;
            if (bytes > 16384) throw MappingFailure.limit();
            output.put(entry.getKey(), value.textValue());
        }
        return output;
    }

    private static boolean matches(JsonNode value, String type) {
        if (type == null) return true;
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private static void keys(JsonNode object, Set<String> allowed, String section) {
        if (object == null || !object.isObject()) throw MappingFailure.configuration(section);
        object.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw MappingFailure.configuration(section);
        });
    }

    private static void validatePath(String path) {
        if (path == null) throw MappingFailure.configuration("field.from");
        if (path.equals("eventId")) return;
        String[] segments = path.split("\\.", -1);
        if (segments.length > MAX_DEPTH || !segments[0].equals("data")) throw MappingFailure.configuration("field.from");
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || !segment.codePoints().allMatch(value -> Character.isLetterOrDigit(value) || value == '_' || value == '-')) {
                throw MappingFailure.configuration("field.from");
            }
        }
    }

    private static void validateConstant(JsonNode value, int depth) {
        if (depth > MAX_DEPTH) throw MappingFailure.configuration("field.constant.depth");
        if (value.isTextual()) unicode(value.textValue());
        if (value.isObject()) value.fieldNames().forEachRemaining(RequestMapper::unicode);
        if (value.isContainerNode()) value.forEach(child -> validateConstant(child, depth + 1));
    }

    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw MappingFailure.configuration("text.encoding");
            } else if (Character.isLowSurrogate(current)) throw MappingFailure.configuration("text.encoding");
        }
    }

    private static boolean hasForbiddenHeaderCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            // Field values allow horizontal tabs but no other C0 controls, CR/LF, NUL or DEL.
            if (character < 0x20 && character != '\t' || character == 0x7f) return true;
        }
        return false;
    }
}
