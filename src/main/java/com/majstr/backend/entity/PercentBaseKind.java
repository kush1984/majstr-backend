package com.majstr.backend.entity;

/**
 * What a «%» line is a percentage OF.
 *
 * <p>Three kinds and no more, because the fourth — a percentage of another percentage — is
 * deliberately impossible. Allowing it would mean a dependency graph, cycle detection, and an order
 * of evaluation nobody could explain; forbidding it turns the whole problem into a filter on what
 * the base picker offers.</p>
 */
public enum PercentBaseKind {

    /** A sum the master typed himself. The base lives in {@code unitPrice}. */
    MANUAL,

    /**
     * Another line of the same estimate — «Доставка 10 % від «Шафа»». Only an ORDINARY line may be
     * named here; that restriction is what makes cycles impossible.
     */
    POSITION,

    /**
     * The estimate's own subtotal, measured before any TOTAL line is added — «Транспортні +20 %».
     * Every TOTAL line uses that same base, so two of them never compound and the result cannot
     * depend on the order they were entered in.
     */
    TOTAL
}
