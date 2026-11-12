package com.majstr.backend.exception;

/** A «Перенести в Majstr» commit the server refuses (an estimate with no object, too many objects). 400 {@code TRANSFER_INVALID}. */
public class TransferValidationException extends RuntimeException {
    public TransferValidationException(String messageKey) {
        super(messageKey);
    }
}
