package com.majstr.backend.entity;

/**
 * The three measurement element kinds, each with a fixed unit. Only elements whose
 * unit matches an estimate line's unit may be substituted into that line's quantity.
 *
 * <ul>
 *   <li>{@code SURFACE} (м²) — ceiling / floor / walls: Σ(l·w) − Σ openings.</li>
 *   <li>{@code PARTITION} (м²) — a partition/box by its faces (H·W left/right,
 *       H·D end, W·D top).</li>
 *   <li>{@code LINEAR} (м.пог) — running metres: window/door reveals, skirting —
 *       perimeter sides × count.</li>
 * </ul>
 */
public enum MeasurementType {
    SURFACE(Unit.M2),
    PARTITION(Unit.M2),
    LINEAR(Unit.LINEAR_METER);

    private final Unit unit;

    MeasurementType(Unit unit) {
        this.unit = unit;
    }

    public Unit unit() {
        return unit;
    }
}
