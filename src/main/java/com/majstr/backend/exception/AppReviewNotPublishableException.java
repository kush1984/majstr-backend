package com.majstr.backend.exception;

/** Publishing a rating of Majstr the master did not agree to show (V218). */
public class AppReviewNotPublishableException extends RuntimeException {
    public AppReviewNotPublishableException(String messageKey) {
        super(messageKey);
    }
}
