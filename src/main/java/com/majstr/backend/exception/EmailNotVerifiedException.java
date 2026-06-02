package com.majstr.backend.exception;

/**
 * Thrown when an action that reaches a client (currently: creating a
 * share link) is attempted by a user whose email isn't verified yet.
 * Maps to 403 with the machine-readable code {@code EMAIL_NOT_VERIFIED}.
 */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
