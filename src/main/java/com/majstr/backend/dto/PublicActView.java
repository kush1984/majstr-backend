package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The client-facing view of ONE work-completion act, reached by its {@code ?a=} share token. A
 * settled document the client reviews and signs — deliberately a fresh, minimal shape (not the
 * owner's {@code WorkActResponse}, which carries internal flags): only what the portal page renders.
 * Units are pre-formatted Ukrainian labels so the page prints them as-is.
 *
 * <p>Never carries anything the client shouldn't see: no economy figures, no other acts, no
 * estimate internals — just this act's own lines and totals.</p>
 */
public record PublicActView(
        String number,
        String title,         // optional stage name («Штукатурні роботи»)
        String kind,          // INTERIM / FINAL
        String status,        // SENT / SIGNED
        LocalDate issuedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String contractRef,
        String objectName,
        String objectAddress,
        String contractorName,
        String clientName,
        List<Item> items,
        List<Receipt> receipts,
        BigDecimal total,
        BigDecimal receiptsTotal,
        BigDecimal advanceOffset,
        BigDecimal payable,
        String payableInWords,
        Instant signedAt,
        String signerName
) {
    public record Item(
            String name,
            String category,
            String estimateName,   // which estimate this line came from (null for additional works)
            String unit,           // pre-formatted label
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {}

    /** A receipt or invoice re-billed on the act: the amount is what counts, the photo is the proof.
     *  {@code hasPhoto} tells the portal page whether to render a link to the image endpoint. */
    public record Receipt(
            UUID id,
            String label,
            LocalDate issuedAt,
            BigDecimal amount,
            boolean hasPhoto
    ) {}
}
