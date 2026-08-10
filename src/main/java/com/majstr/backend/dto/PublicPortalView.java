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
        PublicEstimateView.Contractor contractor,
        PublicEstimateView.ProjectSummary project,
        List<Section> estimates,
        List<PublicEstimateView.SharedPhoto> sharedPhotos,
        /** Null unless the master turned the object-level payments toggle on
         *  ({@code project_share_links.payments_visible}) — off by default. */
        PaymentsCard payments
) {
    /**
     * {@code contractedTotal} sums ONLY the {@code estimates} above (the SHARED subset) — never
     * the master's private "all counted estimates" total, which could include work this client
     * was never shown. Isolation-critical: see {@code PublicEstimateIsolationTest}.
     */
    public record PaymentsCard(
            BigDecimal contractedTotal,
            BigDecimal received,
            BigDecimal remaining,
            List<PaymentRow> payments
    ) {}

    public record PaymentRow(
            String purpose,
            BigDecimal amount,
            BigDecimal paidAmount,
            LocalDate dueDate,
            String nextStage,
            ProjectPaymentStatus status
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
            PublicEstimateView.Signature signature
    ) {}
}
