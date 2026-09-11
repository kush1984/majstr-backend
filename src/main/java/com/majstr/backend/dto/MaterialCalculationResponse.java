package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The answer to «скільки матеріалу купити» for one estimate (V127).
 *
 * <p>Every number here is derived and nothing is stored: the calculation runs on each request, so
 * a corrected quantity in the estimate is reflected the next time the master opens the screen. It
 * lands in the shopping list only when he says so.</p>
 *
 * <p>The server sends numbers, never sentences. Each line carries its {@code sources}, so the
 * screen can render «22 м² × 1,0 = 22 м²» itself — arithmetic the master can check is the whole
 * point of the screen, and a formatted string from the server would be one more place for the
 * Ukrainian to drift out of step with the rest of the app.</p>
 *
 * @param materials     what to buy, one row per material, largest contribution first
 * @param coverage      how much of the estimate the norms actually explain
 * @param parameters    figures the calculation needs and does not have (see {@link MissingParameter})
 * @param wastePercent  the allowance actually applied, so the screen can show which one won
 * @param perimeter     the perimeter used, or null when none was given
 * @param estimateSigned whether the estimate behind these figures is settled. Nothing is gated on
 *                      it: it is said out loud because quantities on an unsigned estimate can still
 *                      move, and a master about to buy deserves to know that
 */
public record MaterialCalculationResponse(
        List<CalculatedMaterialLine> materials,
        MaterialCoverage coverage,
        List<MissingParameter> parameters,
        BigDecimal wastePercent,
        BigDecimal perimeter,
        boolean estimateSigned
) {}
