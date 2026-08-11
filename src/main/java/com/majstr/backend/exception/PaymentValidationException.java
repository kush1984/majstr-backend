package com.majstr.backend.exception;

/**
 * A payment-receipt request the app should have prevented client-side (a label colliding with a
 * plan stage's purpose, an overflow with no resolution, a TRANSFER with no next stage) — the PWA
 * computes these cases itself before submitting, so reaching here means stale client state, not
 * the normal flow. Message is a bundle key, resolved by the advice (→ 400), matching
 * {@link PaymentSplitException}'s convention.
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
