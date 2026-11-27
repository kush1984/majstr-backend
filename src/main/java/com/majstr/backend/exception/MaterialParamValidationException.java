package com.majstr.backend.exception;

/**
 * An answer to one of the material calculator's questions the server cannot read — today a fence's
 * sides that are not a list of lengths (V227). Mapped to 400 with code {@code MATERIAL_PARAM_INVALID}.
 */
public class MaterialParamValidationException extends RuntimeException {
    public MaterialParamValidationException(String messageKey) {
        super(messageKey);
    }
}
