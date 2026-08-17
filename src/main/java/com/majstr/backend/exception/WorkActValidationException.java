package com.majstr.backend.exception;

/**
 * A work-act request that is malformed on its merits rather than a state conflict — e.g. a line that
 * references a position from a kosторис excluded from the economy (which has no income to close
 * against). Carries the message-bundle key + response code, mapped to 400 (acts-fix).
 */
public class WorkActValidationException extends RuntimeException {
    private final String code;

    public WorkActValidationException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
