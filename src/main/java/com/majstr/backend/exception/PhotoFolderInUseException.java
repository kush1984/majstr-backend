package com.majstr.backend.exception;

/**
 * A custom photo folder was deleted while photos still carry its name (photo-folders iteration).
 * Photos reference folders by name, so a delete must never silently re-file them — a state
 * conflict, mapped to 409 with code {@code PHOTO_FOLDER_NOT_EMPTY}.
 */
public class PhotoFolderInUseException extends RuntimeException {
    public PhotoFolderInUseException(String messageKey) {
        super(messageKey);
    }
}
