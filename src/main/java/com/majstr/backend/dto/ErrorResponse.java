package com.majstr.backend.dto;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Long retryAfterSeconds,
        String code
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null, null);
    }

    /** Error with a machine-readable code the client can branch on (e.g. EMAIL_NOT_VERIFIED). */
    public static ErrorResponse coded(int status, String error, String message, String path, String code) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null, code);
    }

    public static ErrorResponse rateLimited(String message, String path, long retryAfterSeconds) {
        return new ErrorResponse(Instant.now(), 429, "Too Many Requests", message, path, retryAfterSeconds, null);
    }
}
