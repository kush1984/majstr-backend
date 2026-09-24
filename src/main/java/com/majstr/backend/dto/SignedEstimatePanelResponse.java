package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One panel in the object economy's per-estimate list — every SIGNED estimate gets one
 * (a signed-estimate panel; the old "act" framing was renamed in the acts iteration, when a real
 * «Акт виконаних робіт» became a separate document), <b>regardless of</b> {@code countInEconomy}.
 * {@code countedInEconomy} is
 * carried through so the PWA can honestly flag a panel whose amount is NOT folded into the
 * summary total below it (owner excluded it, or it's a consolidation source) — never silently.
 *
 * <p>{@code markup}/{@code discount} (economy-rework iteration) mirror the same «% від кошторису»
 * recap the app's black summary panel and the portal show ({@link PublicEstimateView}'s
 * markup/discount, {@code EstimateEditorPage.tsx}'s {@code adjustTotals}) — carried per-panel so
 * the PWA can sum them across the <em>counted</em> panels for the Σ summary above Платежі without
 * a second backend round-trip.</p>
 */
public record SignedEstimatePanelResponse(
        UUID id,
        String name,
        BigDecimal works,
        BigDecimal materials,
        BigDecimal markup,
        BigDecimal discount,
        BigDecimal total,
        boolean countedInEconomy,
        Instant signedAt,
        /** REGULAR vs ADDENDUM (economy-review): an auto-created «Додаткові роботи до акта № N»
         *  panel is money the master never typed as an estimate — the PWA badges it so it doesn't
         *  read as a кошторис he forgot creating. */
        EstimateKind kind,
        /** The percent the markup was actually written at, or null when several «% від кошторису»
         *  lines disagree and one figure would be a number nobody's estimate carries.
         *
         *  <p>Sent rather than derived on the client: such a line is measured against its OWN
         *  TYPE's subtotal, so dividing its amount by works+materials — which is what the panel
         *  did — printed «14,776%» for a discount the master had typed as 15.</p> */
        BigDecimal markupRate,
        /** The same for the discount, kept NEGATIVE like the amount beside it. */
        BigDecimal discountRate,
        /** «Бригаді / Твоя націнка» — present only on a duplicate made with a MARKUP, absent
         *  (null) on everything else. Owner-only: see {@link CrewMarginResponse}. */
        CrewMarginResponse crewMargin
) {}
