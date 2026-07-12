package com.majstr.backend.exception;

/**
 * A measurement element couldn't be computed — malformed or invalid payload
 * (negative dimensions, bad shape). The message is a bundle key resolved by the
 * advice (→ 400), so the master sees a friendly, localized reason.
 */
public class MeasurementException extends RuntimeException {
    public MeasurementException(String messageKey) {
        super(messageKey);
    }
}
