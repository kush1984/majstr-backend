package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A figure the calculation needs and cannot derive: the room's PERIMETER, which a per-m² position
 * cannot carry (a UD track runs around the room, not across the sheathed area); a короб's SECTION,
 * the розгортка (ш+в) of a box sold by the м.п. of its length (V131); or a layer's THICKNESS in
 * millimetres, which is what plaster, screed and levelling compound are actually sold by (V137).
 *
 * <p>Reported WITH the material it is holding up, and with a way to enter it on the screen. The
 * alternative — inferring a perimeter from an area, or a thickness from nothing — would need
 * information we do not have, and the wrong answer costs the master a second trip.</p>
 *
 * <p>{@code estimateItemId} and {@code positionName} say WHICH position is asking, and are set for
 * SECTION and THICKNESS and null for PERIMETER — the difference is not cosmetic: one room has one
 * perimeter, so that question is asked once for the estimate, while every короб line is its own box
 * and one estimate plasters walls at 15 mm and a ceiling at 10. The screen groups by the position
 * and renders one input per question.</p>
 *
 * <p>{@code suggested} is the norm's own {@code default_param}, in that parameter's own unit —
 * metres for a SECTION, millimetres for a THICKNESS. It is a value to PRE-FILL visibly, never one
 * to apply quietly (open-questions §15): the master must see the figure he is about to buy against.
 * Null means we have no honest suggestion — a короб's розгортка is anywhere between 0,2 m and
 * 1,2 m, and pre-filling one would pass a guess off as our answer.</p>
 */
public record MissingParameter(String parameter, String materialName,
                               UUID estimateItemId, String positionName,
                               BigDecimal suggested) {}
