package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One panel in the object economy's per-estimate list — every SIGNED estimate gets one
 * (the "act" framing), <b>regardless of</b> {@code countInEconomy}. {@code countedInEconomy} is
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
        Instant signedAt
) {}
