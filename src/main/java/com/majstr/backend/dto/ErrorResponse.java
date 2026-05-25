package com.majstr.backend.dto;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Long retryAfterSeconds
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse rateLimited(String message, String path, long retryAfterSeconds) {
        return new ErrorResponse(Instant.now(), 429, "Too Many Requests", message, path, retryAfterSeconds);
    }
}
