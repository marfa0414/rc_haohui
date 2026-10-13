package com.rc.notification.application;

import com.rc.notification.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record TaskCursor(Instant createdAt, UUID id) {
    public static TaskCursor parse(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 256) throw ApiException.invalid();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) throw ApiException.invalid();
            Instant instant = Instant.parse(parts[0]);
            if (instant.getEpochSecond() < 0 || instant.getEpochSecond() > 253402300799L) throw ApiException.invalid();
            UUID id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) throw ApiException.invalid();
            return new TaskCursor(instant, id);
        } catch (RuntimeException malformed) {
            throw ApiException.invalid();
        }
    }
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
}
