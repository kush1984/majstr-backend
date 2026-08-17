package com.majstr.backend.exception;

/**
 * Thrown when a mutation targets a work act the client has already signed. A signed act is
 * immutable — the signature certifies an exact set of work and totals. Maps to 409 with code
 * {@code WORK_ACT_SIGNED} (acts iteration).
 */
public class WorkActSignedException extends RuntimeException {
    public WorkActSignedException() {
        super("This work act has been signed and can no longer be modified");
    }
}
