package com.majstr.backend.exception;

/**
 * Thrown when creating a second work act on an object that already has an open (DRAFT/SENT) one —
 * one open act per object. Carries the open act's number for the localized «Спочатку завершіть акт
 * № N» message. Maps to 409 with code {@code WORK_ACT_OPEN} (acts iteration).
 */
public class WorkActOpenException extends RuntimeException {
    private final String number;

    public WorkActOpenException(String number) {
        super("An open work act (№ " + number + ") already exists for this object");
        this.number = number;
    }

    public String getNumber() {
        return number;
    }
}
