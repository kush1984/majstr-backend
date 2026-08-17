package com.majstr.backend.exception;

/**
 * A work-act state conflict other than "signed" or "an open act exists" — a FINAL act already
 * closes the object, or a delete targeted a non-DRAFT/REJECTED act (acts iteration). Carries the
 * message-bundle key + response code, mapped to 409.
 */
public class WorkActConflictException extends RuntimeException {
    private final String code;

    public WorkActConflictException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
