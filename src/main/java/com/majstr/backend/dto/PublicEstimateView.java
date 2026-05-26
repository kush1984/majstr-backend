package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Public-facing snapshot of an estimate served via a share link. Carries
 * only what a client needs to see; specifically excludes contractor email,
 * internal UUIDs of the user / project, and any data about other projects
 * or clients.
 */
public record PublicEstimateView(
        Contractor contractor,
        ProjectSummary project,
        EstimateStatus status,
        LocalDate validUntil,
        String notes,
        Instant createdAt,
        List<PublicEstimateItemView> items,
        BigDecimal worksSubtotal,
        BigDecimal materialsSubtotal,
        BigDecimal total,
        Signature signature
) {
    public record Contractor(
            String companyName,
            String fullName,
            String phone,
            String logoUrl
    ) {}

    public record ProjectSummary(
            String name,
            String address,
            String clientName
    ) {}

    public record Signature(
            Instant signedAt,
            String signerName
    ) {}
}
