package com.rc.notification.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrors.ErrorBody> domain(ApiException failure, HttpServletRequest request) {
        var response = ResponseEntity.status(failure.status());
        if (failure.retryAfterSeconds() != null) response.header("Retry-After", failure.retryAfterSeconds().toString());
        return response.body(new ApiErrors.ErrorBody(failure.code(), failure.getMessage(), ApiErrors.requestId(request)));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiErrors.ErrorBody> storage(RuntimeException failure, HttpServletRequest request) {
        return response(503, "STORAGE_UNAVAILABLE", "Storage could not confirm this operation", request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiErrors.ErrorBody> invalid(Exception failure, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "Request is invalid", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrors.ErrorBody> contentType(Exception failure, HttpServletRequest request) {
        return response(415, "INVALID_REQUEST", "Content-Type must be application/json", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrors.ErrorBody> unexpected(Exception failure, HttpServletRequest request) {
        return response(500, "INTERNAL_ERROR", "The operation could not be completed", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrors.ErrorBody> notFound(Exception failure, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource was not found", request);
    }

    private ResponseEntity<ApiErrors.ErrorBody> response(int status, String code, String message,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrors.ErrorBody(code, message, ApiErrors.requestId(request)));
    }
}
