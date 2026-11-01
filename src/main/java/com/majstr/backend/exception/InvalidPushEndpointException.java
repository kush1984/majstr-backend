package com.majstr.backend.exception;

/** A push subscription whose endpoint is not one of the browsers' push services. */
public class InvalidPushEndpointException extends RuntimeException {

    public InvalidPushEndpointException() {
        super("error.push.endpoint-invalid");
    }
}
