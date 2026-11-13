package com.majstr.backend.exception;

/**
 * A tile layout the server refuses to store: the line is not laid in m², or its counts contradict each
 * other. Mapped to 400 with code {@code TILE_LAYOUT_INVALID}.
 */
public class TileLayoutValidationException extends RuntimeException {
    public TileLayoutValidationException(String messageKey) {
        super(messageKey);
    }
}
