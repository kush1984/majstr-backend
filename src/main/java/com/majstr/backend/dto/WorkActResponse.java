package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A work act with its frozen lines, receipts and computed money. {@code total} sums the included
 * lines, {@code receiptsTotal} the attached receipts;
 * {@code payable} adds the receipts and nets off {@code advanceOffset} (→ «До сплати»). Owner-only.
 */
public record WorkActResponse(
        UUID id,
        UUID projectId,
        String number,
        String title,
        WorkActKind kind,
        WorkActStatus status,
        LocalDate issuedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String place,
        String contractRef,
        String note,
        boolean showMaterials,
        boolean showCumulative,
        boolean receiptsToExpenses,
        boolean showReceiptPhotos,
        BigDecimal advanceOffset,
        BigDecimal retentionPercent,
        Instant sentAt,
        Instant signedAt,
        String signerName,
        boolean signedOffline,
        UUID addendumEstimateId,
        List<WorkActItemResponse> items,
        List<WorkActReceiptResponse> receipts,
        BigDecimal total,
        BigDecimal receiptsTotal,
        BigDecimal payable,
        Instant createdAt,
        Instant updatedAt
) {}
