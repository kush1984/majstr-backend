package com.majstr.backend.dto;

import java.util.UUID;

/**
 * A figure the calculation needs and cannot derive: the room's PERIMETER, which a per-m² position
 * cannot carry (a UD track runs around the room, not across the sheathed area), or a короб's
 * SECTION, the розгортка (ш+в) of a box sold by the м.п. of its length (V131).
 *
 * <p>Reported WITH the material it is holding up, and with a way to enter it on the screen. The
 * alternative — inferring a perimeter from an area, or a section from nothing — would need a shape
 * we do not have, and the wrong answer costs the master a second trip.</p>
 *
 * <p>{@code estimateItemId} and {@code positionName} say WHICH position is asking, and are set for
 * SECTION and null for PERIMETER — the difference is not cosmetic: one room has one perimeter, so
 * that question is asked once for the estimate, while every короб line is its own box and gets its
 * own question. The screen groups by the position and renders one input per box.</p>
 */
public record MissingParameter(String parameter, String materialName,
                               UUID estimateItemId, String positionName) {}
