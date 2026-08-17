package com.majstr.backend.dto;

import java.util.List;

/**
 * The object economy tab's data, split by who may see it:
 *
 * <ul>
 *   <li>{@code estimates} — <b>FREE + PRO, always present, never gated.</b> A panel per SIGNED
 *       estimate (the acts) — "here are the deals I've actually signed" is the one thing every
 *       plan gets to see.</li>
 *   <li>{@code acts} — <b>FREE + PRO, always present, never gated</b> (acts iteration): the works
 *       axis (contracted / accepted-by-acts / received). A contract-vs-work figure, not profit, so
 *       it sits with {@code estimates} on the free side of the split.</li>
 *   <li>{@code payments} + {@code internals} — <b>PRO only</b> (economy-polish iteration moved
 *       {@code payments} here too — it used to be FREE-visible alongside {@code estimates}).
 *       {@code null} for a FREE master; the PWA renders ONE lock teaser covering the Σ summary
 *       panel, the payment schedule, and Прибуток/Витрати together, not three separate gates.</li>
 * </ul>
 *
 * Owner-only — never in the client portal, PDF, or any share-token response.
 */
public record ObjectEconomyResponse(
        List<SignedEstimatePanelResponse> estimates,
        ObjectEconomyActsResponse acts,
        PaymentsSummaryResponse payments,
        ObjectEconomyInternalsResponse internals
) {}
