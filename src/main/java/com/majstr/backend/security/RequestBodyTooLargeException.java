package com.majstr.backend.security;

import java.io.IOException;

/** A request body that kept coming past {@link RequestSizeLimitFilter}'s ceiling. */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
