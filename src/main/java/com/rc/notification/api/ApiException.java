package com.rc.notification.api;

public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiException(int status, String code, String safeMessage) {
        this(status, code, safeMessage, null);
    }
    public ApiException(int status, String code, String safeMessage, Long retryAfterSeconds) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public static ApiException invalid() { return new ApiException(400, "INVALID_REQUEST", "Request is invalid"); }
    public static ApiException routeUnavailable() {
        return new ApiException(422, "ROUTE_UNAVAILABLE", "No usable route configuration is available");
    }
    public static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "Notification was not found"); }
}
