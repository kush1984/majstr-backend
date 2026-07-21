package com.majstr.backend.entity;

/**
 * The measurement element kinds, each with a fixed unit. Only elements whose unit
 * matches an estimate line's unit may be substituted into that line's quantity — that
 * unit match is what lets a new kind ride the existing picker/substitution rails.
 *
 * <ul>
 *   <li>{@code SURFACE} (м²) — ceiling / floor / walls: Σ(l·w) − Σ openings.</li>
 *   <li>{@code PARTITION} (м²) — a partition/box by its faces (H·W left/right,
 *       H·D end, W·D top).</li>
 *   <li>{@code LINEAR} (м.пог) — running metres: window/door reveals, skirting —
 *       perimeter sides × count.</li>
 *   <li>{@code ELECTRICAL_POINTS} (шт) — electrical points read off a plan (sockets,
 *       switches, luminaires, power outlets): Σ of per-type counts. Discrete items only.</li>
 *   <li>{@code SHTROBA} (м.пог) — chasing length: one horizontal bus per room plus a
 *       vertical drop to each point. Deterministic, never LLM-estimated.</li>
 * </ul>
 */
public enum MeasurementType {
    SURFACE(Unit.M2),
    PARTITION(Unit.M2),
    LINEAR(Unit.LINEAR_METER),
    ELECTRICAL_POINTS(Unit.PIECE),
    SHTROBA(Unit.LINEAR_METER),
    CABLE(Unit.M);

    private final Unit unit;

    MeasurementType(Unit unit) {
        this.unit = unit;
    }

    public Unit unit() {
        return unit;
    }
}
