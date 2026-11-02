package com.majstr.backend.exception;

import lombok.Getter;

/** Too much heavy work is already running. Answered as 503 with Retry-After, and not worth a Sentry event. */
@Getter
public class ServerBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public ServerBusyException(long retryAfterSeconds) {
        super("error.server-busy");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
