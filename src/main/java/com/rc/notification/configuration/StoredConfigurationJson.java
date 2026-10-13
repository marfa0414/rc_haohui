package com.rc.notification.configuration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notification.api.ApiException;
import java.math.BigDecimal;

/** PostgreSQL jsonb prints numbers in decimal form; budget that representation before sending it to PG. */
public final class StoredConfigurationJson {
    public static final int MAX_BYTES = 262144;
    private StoredConfigurationJson() { }

    public static ObjectMapper newMapper() {
        return new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxNumberLength(MAX_BYTES).maxStringLength(MAX_BYTES).build()).build())
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    public static String forStorage(JsonNode value) {
        Budget budget = new Budget();
        estimate(value, budget);
        return value.toString();
    }

    private static void estimate(JsonNode value, Budget budget) {
        if (value.isObject()) {
            budget.add(2);
            var fields = value.fields();
            boolean first = true;
            while (fields.hasNext()) {
                var field = fields.next();
                if (!first) budget.add(2);
                first = false;
                string(field.getKey(), budget); budget.add(2); estimate(field.getValue(), budget);
            }
        } else if (value.isArray()) {
            budget.add(2);
            boolean first = true;
            for (JsonNode item : value) {
                if (!first) budget.add(2);
                first = false; estimate(item, budget);
            }
        } else if (value.isTextual()) {
            string(value.textValue(), budget);
        } else if (value.isNumber()) {
            BigDecimal number = value.decimalValue();
            long precision = number.precision(), scale = number.scale();
            long digits = scale <= 0 ? precision - scale : Math.max(precision - scale, 1) + 1 + scale;
            budget.add(digits + (number.signum() < 0 ? 1 : 0));
        } else if (value.isNull()) budget.add(4);
        else if (value.isBoolean()) budget.add(value.booleanValue() ? 4 : 5);
        else throw invalid();
    }

    private static void string(String value, Budget budget) {
        budget.add(2);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            // PostgreSQL jsonb cannot represent U+0000, even when it arrived JSON-escaped.
            if (character == 0) throw invalid();
            if (character == '"' || character == '\\') budget.add(2);
            else if (character < 0x20) budget.add("\b\f\n\r\t".indexOf(character) >= 0 ? 2 : 6);
            else if (character < 0x80) budget.add(1);
            else if (character < 0x800) budget.add(2);
            else if (Character.isHighSurrogate(character)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                budget.add(4);
            } else if (Character.isLowSurrogate(character)) throw invalid();
            else budget.add(3);
        }
    }
    private static final class Budget {
        long remaining = MAX_BYTES;
        void add(long bytes) { if (bytes < 0 || bytes > remaining) throw invalid(); remaining -= bytes; }
    }
    private static ApiException invalid() {
        return new ApiException(400, "INVALID_CONFIGURATION", "Configuration exceeds its stored representation limits");
    }
}
