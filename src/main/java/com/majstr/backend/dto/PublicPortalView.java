package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ProjectPaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public-facing snapshot of the object's portal: one page, a section per
 * estimate the master chose to show. Reuses the nested records of
 * {@link PublicEstimateView} for the shared parts. The estimate id is exposed
 * so the page can address the per-section sign / question / PDF endpoints —
 * it is only reachable through a valid portal token.
 */
public record PublicPortalView(
        /** Which context minted this page — SIGNATURE (Кошторис tab, any-status, for signing,
         *  never payments) or ECONOMY (Економіка tab, SIGNED acts only, optional payments card).
         *  The two link kinds already keep the data separate; this just lets the page (and any
         *  future consumer) branch without re-deriving the mode from which fields are populated. */
        Mode mode,
        PublicEstimateView.Contractor contractor,
        PublicEstimateView.ProjectSummary project,
        List<Section> estimates,
        List<PublicEstimateView.SharedPhoto> sharedPhotos,
        /** Null unless the master turned the object-level payments toggle on
         *  ({@code project_share_links.payments_visible}) — off by default, and never
         *  populated for {@code SIGNATURE} (the signing portal has no payments card at all). */
        PaymentsCard payments
) {
    public enum Mode { SIGNATURE, ECONOMY }

    /**
     * {@code contractedTotal} sums ONLY the {@code estimates} above (the SHARED subset) — never
     * the master's private "all counted estimates" total, which could include work this client
     * was never shown. Isolation-critical: see {@code PublicEstimateIsolationTest}.
     */
    public record PaymentsCard(
            BigDecimal contractedTotal,
            BigDecimal received,
            BigDecimal remaining,
            /** Of {@code received}, how much was the client paying the master back for material
             *  (review B-65) — every payment ticked «на матеріал» (object-money iteration: no longer
             *  capped at the receipts), because that is exactly the gap between «Отримано» and what
             *  bought work. It is money that
             *  arrived, so it stays inside {@code received} and the itemized rows still add up; it
             *  bought no work, so {@code remaining} does not shrink by it. Named here so the client
             *  is told why, instead of being handed two figures that look like a slip. */
            BigDecimal materialRefundApplied,
            /** Of the client's material money, what paid for the material his signed acts bill
             *  ({@code MaterialRefundSplit.toActs}) — counted as paid, so {@code remaining} does
             *  shrink by it; named so the client sees where his «на матеріал» money went. */
            BigDecimal materialToActs,
            /** Every payment that arrived, newest last — the payment plan is gone from the app
             *  (the master can no longer edit or delete a stage), so the client is shown money that
             *  LANDED, never a stage still «due» or «overdue» (review 2026-11-04). A receipt against
             *  an old stage is labelled by that stage when it has no label of its own. */
            List<ReceiptRow> receipts
    ) {}

    public record ReceiptRow(
            String label,
            BigDecimal amount,
            LocalDate receivedAt
    ) {}

    /**
     * {@code depositAmount}/{@code balance} were removed (payments-economy-portal iteration) —
     * money is object-level now, shown once via {@link PublicPortalView#payments()} rather than
     * duplicated per section (two sources of truth for the same number). The legacy per-estimate
     * {@code ?t=} view ({@link PublicEstimateView}) is untouched — it still carries its own frozen
     * {@code depositAmount}/{@code balance} for URLs already sent out before this iteration.
     */
    public record Section(
            UUID id,
            String name,
            EstimateStatus status,
            LocalDate validUntil,
            String notes,
            Instant createdAt,
            List<PublicEstimateItemView> items,
            BigDecimal worksSubtotal,
            BigDecimal materialsSubtotal,
            BigDecimal total,
            /** Σ of TOTAL-based / frozen PERCENT lines with a positive amount — the same
             *  «Надбавка» recap the app's black summary panel shows. Zero when none. */
            BigDecimal markupAmount,
            /** Same, negative lines — «Знижка». Zero when none. */
            BigDecimal discountAmount,
            /** Mirrors {@link PublicEstimateView#markupPercent()} — see there for the rule. */
            BigDecimal markupPercent,
            /** Mirrors {@link PublicEstimateView#discountPercent()}. */
            BigDecimal discountPercent,
            PublicEstimateView.Signature signature,
            /** Mirrors {@link PublicEstimateView#version()} — the portal signs a SECTION, so the
             *  version must travel per section, not per page (B-61). */
            long version
    ) {}
}
