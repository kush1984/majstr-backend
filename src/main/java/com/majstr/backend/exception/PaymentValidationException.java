package com.majstr.backend.exception;

/**
 * A payment-receipt request the app should have prevented client-side (no label, a material refund
 * on an old plan stage). Message is a bundle key, resolved by the advice (→ 400).
 */
public class PaymentValidationException extends RuntimeException {
    private final transient Object[] args;

    public PaymentValidationException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }
}
