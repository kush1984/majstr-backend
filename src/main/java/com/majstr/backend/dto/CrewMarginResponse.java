package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * «Бригаді / Твоя націнка» on a duplicate made with a markup — the бригадир's own half of the
 * money, computed from what he typed rather than from what he remembered to record.
 *
 * <p><b>Owner-only, and that is an invariant, not a habit.</b> Crew prices are the one number in
 * this product that must never reach the person paying: not in the portal, not in a PDF, not
 * through any share token. No public DTO tree may carry this record or
 * {@code EstimateItemResponse.sourceUnitPrice}; {@code PublicEstimateIsolationTest} fails the build
 * if one ever does.</p>
 *
 * <p>Absent (null) for anything the rule does not cover: an ordinary estimate, and a duplicate made
 * with a DISCOUNT — a cheaper offer to the client is not a crew sheet, so there is no margin to
 * report.</p>
 *
 * @param crewTotal      what the same sheet came to at the crew's own prices
 * @param margin         client total − crew total. May be negative: the master can knowingly sell a
 *                       position below what the crew charges him
 * @param marginAccepted the part of that margin the client has already accepted by signing acts.
 *                       Counted over act lines that point at THIS copy's lines — an off-estimate
 *                       act line has no crew price anywhere, so unlike «Прийнято актами» it cannot
 *                       be included here (see the repository query for the whole argument)
 * @param unpricedCount  lines added to the copy after it was made, which carry no crew price. Their
 *                       contribution to the margin is ZERO — the crew may well be doing that work —
 *                       and the screen names them instead of quietly inflating the figure
 * @param unpricedTotal  what those lines come to for the client
 */
public record CrewMarginResponse(
        BigDecimal crewTotal,
        BigDecimal margin,
        BigDecimal marginAccepted,
        int unpricedCount,
        BigDecimal unpricedTotal
) {}
