package com.majstr.backend.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * Thrown when the master changes an object his plan leaves read-only ({@code ObjectWriteGuard}): on
 * FREE only the first objects he ever made are his to change. Maps to 403 with code
 * {@code OBJECT_READ_ONLY}; the user-facing text is {@code error.object.read-only}.
 */
@Getter
public class ObjectReadOnlyException extends RuntimeException {

    private final int writableObjects;

    public ObjectReadOnlyException(UUID projectId, int writableObjects) {
        super("Object " + projectId + " is read-only on this plan (first " + writableObjects + " objects only)");
        this.writableObjects = writableObjects;
    }
}
