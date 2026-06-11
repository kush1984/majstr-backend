package com.majstr.backend.exception;

/**
 * Thrown on a status transition the contractor isn't allowed to make —
 * today only setting SIGNED manually (a signature must come from the client
 * via the portal so the signer metadata is real). Maps to 400.
 */
public class InvalidEstimateStatusException extends RuntimeException {

    public InvalidEstimateStatusException(String message) {
        super(message);
    }
}
