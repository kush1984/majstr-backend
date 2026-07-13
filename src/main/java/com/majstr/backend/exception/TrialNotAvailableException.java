package com.majstr.backend.exception;

/**
 * The self-serve PRO trial can't be started — the master already used their
 * one-time trial, or is not currently on FREE. Mapped to 409 with code
 * {@code TRIAL_UNAVAILABLE} so the PWA can hide the button and explain.
 */
public class TrialNotAvailableException extends RuntimeException {
    public TrialNotAvailableException(String message) {
        super(message);
    }
}
