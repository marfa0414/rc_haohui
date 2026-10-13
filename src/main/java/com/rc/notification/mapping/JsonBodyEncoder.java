package com.rc.notification.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

public final class JsonBodyEncoder implements BodyEncoder {
    private final ObjectMapper mapper;
    public JsonBodyEncoder(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public String format() { return "json"; }
    @Override public String contentType() { return "application/json"; }
    @Override public byte[] encode(ObjectNode fields) {
        BoundedOutput output = new BoundedOutput();
        try {
            mapper.writeValue(output, fields);
            return output.bytes();
        } catch (IOException failed) {
            // Jackson can wrap the limit failure with property-path diagnostics; never expose them.
            Throwable cause = failed;
            while (cause != null) {
                if (cause instanceof MappingFailure mapping) throw mapping;
                cause = cause.getCause();
            }
            throw MappingFailure.configuration("body");
        }
    }
}
