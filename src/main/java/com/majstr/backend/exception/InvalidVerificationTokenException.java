package com.majstr.backend.exception;

/**
 * Email-verification token is missing, expired or already used. Maps to 400
 * (not 401 — it's a bad request body on a public endpoint, not an auth failure).
 */
public class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException(String message) {
        super(message);
    }
}
