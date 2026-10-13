package com.rc.notification.application;

import java.util.Map;

public record RequestSnapshot(String url, String method, Map<String, String> headers,
                              String contentType, byte[] body) {
    public RequestSnapshot {
        headers = Map.copyOf(headers);
        body = body.clone();
    }
    @Override public byte[] body() { return body.clone(); }
}
