package com.majstr.backend.exception;

import lombok.Getter;

/**
 * Generic 429 for endpoint-level rate limits (e.g. resend-verification).
 * Carries the retry-after hint so the handler can set the header and body.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
