package com.majstr.backend.exception;

/**
 * Password-reset token is missing, expired or already used. Maps to 400 with the code
 * {@code INVALID_OR_EXPIRED_TOKEN} so the PWA can show "the link expired, try again"
 * without parsing the message. Mirrors {@link InvalidVerificationTokenException}.
 */
public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException(String message) {
        super(message);
    }
}
