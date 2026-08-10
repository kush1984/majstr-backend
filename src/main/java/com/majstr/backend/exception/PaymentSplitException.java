package com.majstr.backend.exception;

/**
 * A custom split's percents don't sum to 100. The message is a bundle key resolved by the
 * advice (→ 400), matching {@link MeasurementException}'s convention.
 */
public class PaymentSplitException extends RuntimeException {
    public PaymentSplitException(String messageKey) {
        super(messageKey);
    }
}
