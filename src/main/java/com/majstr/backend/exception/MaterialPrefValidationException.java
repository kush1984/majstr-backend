package com.majstr.backend.exception;

/**
 * A material preference the server refuses — an unknown key, or a value longer than the column
 * (material-calculator iteration). Mapped to 400 with code {@code MATERIAL_PREF_INVALID}.
 */
public class MaterialPrefValidationException extends RuntimeException {
    public MaterialPrefValidationException(String messageKey) {
        super(messageKey);
    }
}
