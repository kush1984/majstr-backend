package com.majstr.backend.exception;

/**
 * Thrown when a document is about to leave for a client (a share link, a portal, a letter, a PDF)
 * from an account with no name yet — registration no longer asks for one (V190). Maps to 403 with
 * the machine-readable code {@code SENDER_NAME_REQUIRED}, the twin of {@code EMAIL_NOT_VERIFIED}:
 * the PWA answers it by asking «як вас підписати в документі?» and retrying.
 */
public class SenderNameMissingException extends RuntimeException {
    public SenderNameMissingException(String message) {
        super(message);
    }
}
