package com.rc.notification.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class FormBodyEncoder implements BodyEncoder {
    @Override public String format() { return "form"; }
    @Override public String contentType() { return "application/x-www-form-urlencoded"; }
    @Override public byte[] encode(ObjectNode fields) {
        BoundedOutput output = new BoundedOutput();
        boolean first = true;
        var entries = fields.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode value = entry.getValue();
            if (!scalar(value) || value.isTextual() && value.textValue().isEmpty()) throw MappingFailure.input();
            if (!first) output.write('&');
            first = false;
            write(output, entry.getKey());
            output.write('=');
            write(output, value.asText());
        }
        return output.bytes();
    }
    static boolean scalar(JsonNode value) {
        return value != null && (value.isTextual() || value.isNumber() || value.isBoolean());
    }
    private static void write(BoundedOutput output, String value) {
        byte[] encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).getBytes(StandardCharsets.US_ASCII);
        output.write(encoded, 0, encoded.length);
    }
}
