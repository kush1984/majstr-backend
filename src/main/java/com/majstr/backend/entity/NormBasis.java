package com.majstr.backend.entity;

/**
 * What a {@link MaterialNorm}'s quantity multiplies (V127).
 *
 * <p>{@link #QUANTITY} is every norm but one. {@link #PERIMETER} exists because a UD track runs
 * around the room, not across the sheathed area: no per-m2 figure can produce it, and inferring a
 * perimeter from an area needs a room shape we do not have. Such a norm is therefore reported as a
 * missing parameter <b>with a way to enter it</b> — never quietly derived, never silently dropped.
 * </p>
 */
public enum NormBasis {
    QUANTITY,
    PERIMETER
}
