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
 */
public enum MaterialPrefKey {
    /** Tile format the master usually lays, e.g. {@code 600x600}. */
    TILE_SIZE,
    /** Joint width in millimetres. */
    TILE_JOINT_MM,
    /** Tile thickness in millimetres — grout consumption depends on it. */
    TILE_THICKNESS_MM,
    /** Layout habit, e.g. straight or diagonal — drives the waste allowance. */
    TILE_LAYOUT,
    /** Litres of paint per m² per coat, as the master measures it. */
    PAINT_COVERAGE,
    /** How many coats he paints by default. */
    PAINT_COATS,
    /** Drywall sheet format he buys, e.g. {@code 1200x2500}. */
    GKL_SHEET,
    /** Default waste allowance in percent, applied when a norm carries none. */
    WASTE_PERCENT
}
