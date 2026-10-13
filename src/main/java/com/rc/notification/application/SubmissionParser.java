package com.rc.notification.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rc.notification.api.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SubmissionParser {
    public static final int MAX_BYTES = 262144;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxNumberLength(1000).maxStringLength(MAX_BYTES).build())
            .build();
    private final CanonicalDigest canonical = new CanonicalDigest();

    public Submission parse(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new ApiException(413, "PAYLOAD_TOO_LARGE", "Request exceeds the byte limit");
        try (JsonParser parser = FACTORY.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw ApiException.invalid();
            ObjectNode document = (ObjectNode) value(parser);
            if (parser.nextToken() != null || document.size() != 4) throw ApiException.invalid();
            var fields = document.fieldNames();
            while (fields.hasNext()) {
                if (!Set.of("tenantId", "operation", "eventId", "data").contains(fields.next())) throw ApiException.invalid();
            }
            String tenant = identifier(document.get("tenantId"), 64);
            String operation = identifier(document.get("operation"), 128);
            String event = identifier(document.get("eventId"), 128);
            if (!document.path("data").isObject()) throw ApiException.invalid();
            return new Submission(tenant, operation, event, document, canonical.digest(document));
        } catch (ApiException failure) {
            throw failure;
        } catch (IOException | ArithmeticException | NumberFormatException malformed) {
            // Parser diagnostics contain request values and are never returned or logged.
            throw ApiException.invalid();
        }
    }

    public static String identifier(JsonNode value, int maxLength) {
        if (value == null || !value.isTextual()) throw ApiException.invalid();
        return identifier(value.textValue(), maxLength);
    }

    public static String identifier(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength || !value.matches("[A-Za-z0-9._:-]+")) {
            throw ApiException.invalid();
        }
        return value;
    }

    private JsonNode value(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            ObjectNode object = NODES.objectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw ApiException.invalid();
                String key = unicode(parser.currentName());
                if (parser.nextToken() == null) throw ApiException.invalid();
                object.set(key, value(parser));
            }
            return object;
        }
        if (token == JsonToken.START_ARRAY) {
            ArrayNode array = NODES.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) throw ApiException.invalid();
                array.add(value(parser));
            }
            return array;
        }
        if (token == JsonToken.VALUE_STRING) return NODES.textNode(unicode(parser.getText()));
        if (token == JsonToken.VALUE_TRUE) return NODES.booleanNode(true);
        if (token == JsonToken.VALUE_FALSE) return NODES.booleanNode(false);
        if (token == JsonToken.VALUE_NULL) return NODES.nullNode();
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            String number = parser.getText();
            if (number.length() > 1000) throw ApiException.invalid();
            int exponent = Math.max(number.indexOf('e'), number.indexOf('E'));
            if (exponent >= 0 && new BigInteger(number.substring(exponent + 1)).abs().compareTo(BigInteger.valueOf(10000)) > 0) {
                throw ApiException.invalid();
            }
            return token == JsonToken.VALUE_NUMBER_INT
                    ? NODES.numberNode(new BigInteger(number)) : NODES.numberNode(new BigDecimal(number));
        }
        throw ApiException.invalid();
    }

    static String unicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw ApiException.invalid();
            } else if (Character.isLowSurrogate(current)) {
                throw ApiException.invalid();
            }
        }
        return value;
    }
}
