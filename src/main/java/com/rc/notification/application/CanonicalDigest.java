package com.rc.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Service-local typed canonical encoding. Numeric exponent notation never expands to plain decimal. */
public final class CanonicalDigest {
    public byte[] digest(JsonNode document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, document);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }

    private void add(MessageDigest digest, JsonNode node) {
        if (node.isObject()) {
            update(digest, "{");
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(CanonicalDigest::compareCodePoints);
            for (String key : keys) {
                string(digest, key);
                add(digest, node.get(key));
            }
            update(digest, "}");
        } else if (node.isArray()) {
            update(digest, "[");
            node.forEach(element -> add(digest, element));
            update(digest, "]");
        } else if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            update(digest, "n" + number.toString() + ";");
        } else if (node.isTextual()) {
            string(digest, node.textValue());
        } else if (node.isBoolean()) {
            update(digest, node.booleanValue() ? "t" : "f");
        } else if (node.isNull()) {
            update(digest, "z");
        } else {
            throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    private void string(MessageDigest digest, String value) {
        byte[] utf8 = SubmissionParser.unicode(value).getBytes(StandardCharsets.UTF_8);
        update(digest, "s" + utf8.length + ":");
        digest.update(utf8);
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int compareCodePoints(String first, String second) {
        int a = 0, b = 0;
        while (a < first.length() && b < second.length()) {
            int left = first.codePointAt(a), right = second.codePointAt(b);
            if (left != right) return Integer.compare(left, right);
            a += Character.charCount(left);
            b += Character.charCount(right);
        }
        return Integer.compare(first.length() - a, second.length() - b);
    }
}
