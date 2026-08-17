package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * The object's "works axis" (acts iteration, prompt 5) — how much of the contract the client has
 * actually accepted via SIGNED work acts, next to how much money has come in. Computed
 * <b>unconditionally</b> (FREE-visible, like the per-estimate panels): these are contract/act/received
 * figures, not profit.
 *
 * <ul>
 *   <li>{@code contracted} — Σ counted SIGNED estimates incl. ADDENDUM («За договором»), the same
 *       aggregate {@code PaymentsSummaryResponse.contractedTotal} uses, so the two never disagree.</li>
 *   <li>{@code acceptedByActs} — Σ line totals over the object's SIGNED acts («Прийнято актами»).</li>
 *   <li>{@code received} — Σ payment receipts for the object («Отримано грошей»).</li>
 * </ul>
 *
 * The PWA derives the balance line: {@code acceptedByActs − received} < 0 → «Невідпрацьований аванс»
 * (the master owes work), > 0 → «Заборгованість замовника», 0 → «Розрахунки збігаються».
 */
public record ObjectEconomyActsResponse(
        BigDecimal contracted,
        BigDecimal acceptedByActs,
        BigDecimal received
) {}
