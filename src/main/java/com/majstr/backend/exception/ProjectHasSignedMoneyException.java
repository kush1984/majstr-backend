package com.majstr.backend.exception;

/**
 * Deleting this object would take a signature or recorded money with it (review B-70) — 409
 * {@code PROJECT_HAS_SIGNED_MONEY}. The object stays where it already is: archived.
 */
public class ProjectHasSignedMoneyException extends RuntimeException {

    public ProjectHasSignedMoneyException() {
        super("error.project.has-signed-money");
    }
}
