package com.majstr.backend.exception;

/**
 * Thrown when an estimate's status doesn't allow the operation being attempted — setting SIGNED
 * manually (a signature must come from the client via the portal so the signer metadata is real),
 * or publishing a non-SIGNED estimate to the ECONOMY portal (a settled-money view, not a second
 * place to sign something). Maps to 400.
 */
public class InvalidEstimateStatusException extends RuntimeException {

    public InvalidEstimateStatusException(String message) {
        super(message);
    }
}
