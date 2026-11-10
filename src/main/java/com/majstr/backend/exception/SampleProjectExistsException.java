package com.majstr.backend.exception;

/**
 * The master already has a training sample (V197) — 409 {@code SAMPLE_PROJECT_EXISTS}. One live sample
 * per account; deleting it lets him make it again.
 */
public class SampleProjectExistsException extends RuntimeException {

    public SampleProjectExistsException() {
        super("error.sample.exists");
    }
}
