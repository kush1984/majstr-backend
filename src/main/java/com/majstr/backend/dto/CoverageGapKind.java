package com.majstr.backend.dto;

/** Why a position is not covered. The master reads this, so each value has to mean something. */
public enum CoverageGapKind {
    /** No norm at all for this name and unit — we simply do not know this work yet. */
    NO_NORM,
    /**
     * Norms exist under more than one trade and they disagree about what the position is. Guessing
     * would put someone else's material on the list, so nothing is counted and the position is
     * named instead.
     */
    AMBIGUOUS
}
