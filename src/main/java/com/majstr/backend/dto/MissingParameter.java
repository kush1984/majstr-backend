package com.majstr.backend.dto;

/**
 * A figure the calculation needs and cannot derive — today only the room's PERIMETER, which a
 * per-m² position cannot carry (a UD track runs around the room, not across the sheathed area).
 *
 * <p>Reported WITH the material it is holding up, and with a way to enter it on the screen. The
 * alternative — inferring a perimeter from an area — would need a room shape we do not have, and
 * the wrong answer costs the master a second trip.</p>
 */
public record MissingParameter(String parameter, String materialName) {}
