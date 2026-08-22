package com.majstr.backend.exception;

/**
 * A photo-folder name the server refuses — blank, reserved, or longer than the column
 * (photo-folders iteration). Carries the message-bundle key, mapped to 400 with code
 * {@code PHOTO_FOLDER_INVALID}. (It used to reuse {@code UnsupportedMediaTypeException}, which
 * answered 415 for what is a plain validation error.)
 */
public class PhotoFolderValidationException extends RuntimeException {
    public PhotoFolderValidationException(String messageKey) {
        super(messageKey);
    }
}
