package com.rc.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public final class ApiErrors {
    private ApiErrors() { }
    public static final String REQUEST_ID_ATTRIBUTE = ApiErrors.class.getName() + ".requestId";
    public record ErrorBody(String code, String message, String requestId) { }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing != null) return existing.toString();
        String id = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
        return id;
    }

    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                             int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), new ErrorBody(code, message, requestId(request)));
    }
}
