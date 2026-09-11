package com.majstr.backend.dto;

import java.util.List;

/**
 * «Порахував матеріали для 12 з 15 позицій. 3 не знаю» — said out loud, before the master asks.
 *
 * <p>A calculator that quietly skips what it cannot do is worse than one that admits it: the master
 * drives to the shop with a list that looks complete. So the ratio is stated up front and every
 * uncovered position is named.</p>
 *
 * <p>What is NOT in the denominator: {@code PERCENT} lines (a surcharge is not work and consumes
 * nothing) and materials the master already listed himself (we are not asked to explain those).
 * What IS in the numerator: a position whose norm says «consumes nothing» — that is an answer.</p>
 */
public record MaterialCoverage(int total, int covered, List<CoverageGap> gaps) {}
