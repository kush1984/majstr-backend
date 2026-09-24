package com.majstr.backend.entity;

/**
 * The master's habitual answers to questions the calculator would otherwise ask on every object
 * (V126). Mirrors the {@code master_material_pref_key_check} CHECK — add a constant here and the
 * migration extending that CHECK in the same change, or the insert fails at runtime.
 *
 * <p>A key belongs here only if it is a HABIT: something true of the MASTER (which sheet he buys,
 * how many coats he paints) rather than of the object. A room's perimeter or ceiling height is a
 * property of the flat, has no meaningful default, and pre-filling it from the previous object
 * would announce someone else's number as this master's answer.</p>
 *
 * <p>V137 dropped three of V126's keys for failing that test. {@code TILE_SIZE} and
 * {@code TILE_THICKNESS_MM} are properties of the WORK, and the catalog already names them
 * («Укладання плитки 300х600», «Укладання плитки 1200х3200 мм»); {@code TILE_LAYOUT} is its own
 * PERCENT position («по діагоналі», «ялинкою»). Worse, one answer per MASTER is silently wrong for
 * an estimate mixing 300×300 floor tile with 600×1200 wall tile — which is most bathrooms.</p>
 */
public enum MaterialPrefKey {
    /**
     * Joint width in millimetres. Grout scales linearly with it: the shipped norms assume
     * {@code DEFAULT_TILE_JOINT_MM}, and his answer rescales them.
     */
    TILE_JOINT_MM,
    /** Square metres one litre of paint covers in ONE coat, as the master measures it. */
    PAINT_COVERAGE,
    /** How many coats he paints by default. */
    PAINT_COATS,
    /** Drywall sheet format he buys, e.g. {@code 1200x2500}. */
    GKL_SHEET,
    /** Default waste allowance in percent, applied when a norm carries none. */
    WASTE_PERCENT
}
