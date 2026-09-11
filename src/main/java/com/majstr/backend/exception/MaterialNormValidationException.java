package com.majstr.backend.exception;

/**
 * A consumption norm the server refuses to store — today only «this work consumes nothing», which
 * has no coefficient to correct. Mapped to 400 with code {@code MATERIAL_NORM_INVALID}.
 */
public class MaterialNormValidationException extends RuntimeException {
    public MaterialNormValidationException(String messageKey) {
        super(messageKey);
    }
}
