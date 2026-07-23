package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;

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
        List<PublicEstimateView.SharedPhoto> sharedPhotos
) {
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
            BigDecimal depositAmount,
            BigDecimal balance,
            PublicEstimateView.Signature signature
    ) {}
}
