package com.majstr.backend.exception;

/** An admin plan term the server refuses (V199): a past date, or months and a date together. 400 {@code PLAN_TERM_INVALID}. */
public class PlanTermValidationException extends RuntimeException {
    public PlanTermValidationException(String messageKey) {
        super(messageKey);
    }
}
