package com.rc.notification.auth;

import java.util.List;

/** Only controlled codes are exposed; provider payloads and exception causes are deliberately excluded. */
public final class TokenFailure extends Exception {
    private final String code;
    private final boolean retryable;
    private final List<String> retryAfterValues;

    public TokenFailure(String code, boolean retryable, List<String> retryAfterValues) {
        super(safe(code));
        this.code = code;
        this.retryable = retryable;
        this.retryAfterValues = List.copyOf(retryAfterValues);
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public List<String> retryAfterValues() { return retryAfterValues; }
    private static String safe(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid token failure code");
        return value;
    }
}
