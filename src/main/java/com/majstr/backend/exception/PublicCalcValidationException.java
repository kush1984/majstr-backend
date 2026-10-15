package com.majstr.backend.exception;

/** A public calculator's answers that do not fit together — a 400 with the bundle key as message. */
public class PublicCalcValidationException extends RuntimeException {
    public PublicCalcValidationException(String messageKey) {
        super(messageKey);
    }
}
