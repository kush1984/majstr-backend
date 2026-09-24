package com.majstr.backend.entity;

/**
 * What a {@link MaterialNorm}'s quantity multiplies (V127).
 *
 * <p>{@link #QUANTITY} is almost every norm. The other three exist for the same reason and obey the
 * same rule: they are figures the position cannot carry, so they are <b>asked for</b> and reported
 * as a missing parameter <b>with a way to enter it</b> — never quietly derived, never silently
 * dropped.</p>
 *
 * <p>{@link #PERIMETER} — a UD track runs around the room, not across the sheathed area: no per-m2
 * figure can produce it, and inferring a perimeter from an area needs a room shape we do not have.
 * Applied ONCE per estimate: one room has one perimeter.</p>
 *
 * <p>{@link #SECTION} — a короб is sold by the м.п. of its length, and its sheathing is the
 * розгортка (ш+в) of a box the name does not describe (V131). A SECTION norm is simply a per-m2
 * norm whose m2 is <b>length × section</b>, which is why a короб's longitudinal profiles stay
 * {@link #QUANTITY} (they run per м.п.) while board, ribs and screws are SECTION. Applied once per
 * POSITION, never merged: a короб and a ніша in one estimate are different boxes, and one number
 * for both would be silently wrong for one of them.</p>
 *
 * <p>{@link #THICKNESS} — plaster, screed, levelling compound and start putty are consumed per m2
 * PER MILLIMETRE, so the millimetres ARE the bill: the same «Стяжка маякова цементна» is 40 kg/m2
 * at 20 mm and 120 at 60. A position name carries at most a bound («до 2 см» is not a thickness),
 * and the answer belongs to the POSITION, not the master — one estimate plasters walls at 15 mm and
 * a ceiling at 10. Arithmetically identical to {@link #SECTION}: amount = quantity × mm ×
 * qtyPerUnit, so a THICKNESS norm's coefficient is written per m2 PER MM (V137).</p>
 *
 * <p>Unlike a короб's розгортка, a thickness HAS an honest suggestion, which
 * {@link MaterialNorm#getDefaultParam()} carries so the app can pre-fill the field visibly.</p>
 */
public enum NormBasis {
    QUANTITY,
    PERIMETER,
    SECTION,
    THICKNESS
}
