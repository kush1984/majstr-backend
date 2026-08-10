package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        BigDecimal depositAmount,
        BigDecimal balance,
        /** Σ of TOTAL-based / frozen PERCENT lines with a positive amount — the same
         *  «Надбавка» recap the app's black summary panel shows. Zero when none. */
        BigDecimal markupAmount,
        /** Same, negative lines — «Знижка». Zero when none. */
        BigDecimal discountAmount,
        /** The single live TOTAL-kind line's own quantity, mirroring {@code TypeBreakdown}'s
         *  "exactly one contributing line, none of it frozen" rule — null falls back to the
         *  sum-only {@link #markupAmount} display. */
        BigDecimal markupPercent,
        /** Same rule for {@link #discountAmount}. */
        BigDecimal discountPercent,
        Signature signature,
        List<SharedPhoto> sharedPhotos
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

    /** A progress photo the master shared with the client. The portal streams the file from
     *  {@code /api/public/estimates/{token}/photos/{id}/file} — the storage key stays server-side. */
    public record SharedPhoto(
            UUID id,
            String caption
    ) {}
}
