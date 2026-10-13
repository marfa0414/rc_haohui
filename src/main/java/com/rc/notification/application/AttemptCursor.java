package com.rc.notification.application;

import com.rc.notification.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record AttemptCursor(int runNumber, int attemptNo) {
    public static AttemptCursor parse(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 128) throw ApiException.invalid();
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!raw.matches("[1-9][0-9]{0,9}\\|[1-9][0-9]{0,9}")) throw ApiException.invalid();
            String[] parts = raw.split("\\|", -1);
            return new AttemptCursor(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (RuntimeException malformed) { throw ApiException.invalid(); }
    }
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((runNumber + "|" + attemptNo).getBytes(StandardCharsets.US_ASCII));
    }
}
