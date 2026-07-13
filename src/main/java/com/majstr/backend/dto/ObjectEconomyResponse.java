package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * The object-economy summary (PRO): honest per-object money, keyed off the estimate(s)
 * the master flagged to count (the accepted deal — no double-counting).
 *
 * <p>The model separates the master's <b>earnings</b> (labour) from the materials cash-flow:
 * <ul>
 *   <li>{@code works} — Σ WORK line totals of the counted estimates (the master's labour value).</li>
 *   <li>{@code materials} — Σ MATERIAL line totals of the counted estimates (passthrough; reference).</li>
 *   <li>{@code received} — Σ deposits (завдаток) of the counted estimates.</li>
 *   <li>{@code spentReceipts} — Σ RECEIPT-source expenses (real material cost, from receipt import).</li>
 *   <li>{@code spentManual} — Σ MANUAL-source expenses (unforeseen, hand-entered).</li>
 *   <li>{@code cashBalance} = {@code received − spentReceipts} — the materials pot; <b>NOT clamped</b>:
 *       negative means the master funded materials out of pocket (deposit 3000 − receipts 5000 = −2000).</li>
 *   <li>{@code profit} = {@code works − spentManual}, PLUS {@code cashBalance} once the object is
 *       <b>COMPLETED</b> (a leftover deposit becomes earnings; an overspend reduces them). Materials
 *       themselves are never earnings — they only settle into profit via the deposit at close.</li>
 * </ul>
 *
 * Owner-only — never in the client portal, PDF, or any share-token response.
 */
public record ObjectEconomyResponse(
        BigDecimal works,
        BigDecimal materials,
        BigDecimal received,
        BigDecimal spentReceipts,
        BigDecimal spentManual,
        BigDecimal profit,
        BigDecimal cashBalance
) {}
