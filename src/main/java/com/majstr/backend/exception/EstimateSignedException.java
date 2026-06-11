package com.majstr.backend.exception;

/**
 * Thrown when a mutation targets an estimate the client has already signed.
 * A signed estimate is immutable — the signature certifies an exact set of
 * items and totals. Maps to 409 with code {@code ESTIMATE_SIGNED}.
 */
public class EstimateSignedException extends RuntimeException {

    public EstimateSignedException() {
        super("This estimate has been signed by the client and can no longer be modified");
    }
}
