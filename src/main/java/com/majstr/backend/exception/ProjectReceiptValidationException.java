package com.majstr.backend.exception;

/**
 * An object-receipt request the server refuses — a missing photo, an over-long label, an amount out
 * of range, or too many receipts on one object. Carries the message-bundle key + response code,
 * mapped to 400 (V129).
 */
public class ProjectReceiptValidationException extends RuntimeException {
    private final String code;

    public ProjectReceiptValidationException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
