package com.majstr.backend.exception;

import lombok.Getter;

/**
 * Unlocking a view-only object with a bought object credit was refused (iteration-read-only-objects):
 * the object is already writable, or there is no credit to spend. Maps to 409 with {@link #getCode()};
 * the message is a bundle key.
 */
@Getter
public class ObjectUnlockException extends RuntimeException {

    private final String code;

    public ObjectUnlockException(String messageKey, String code) {
        super(messageKey);
        this.code = code;
    }
}
