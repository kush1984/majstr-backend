package com.majstr.backend.exception;

/**
 * Thrown when emailing an estimate to a client but that client has no email
 * on file. Maps to 400 with the code {@code CLIENT_EMAIL_MISSING}.
 */
public class ClientEmailMissingException extends RuntimeException {
    public ClientEmailMissingException(String message) {
        super(message);
    }
}
