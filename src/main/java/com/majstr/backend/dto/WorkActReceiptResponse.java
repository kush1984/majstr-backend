package com.majstr.backend.dto;

import com.majstr.backend.entity.WorkActReceipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One receipt attached to a work act. {@code hasPhoto} tells the client whether a file endpoint
 *  exists for it — the storage key itself is never exposed. */
public record WorkActReceiptResponse(
        UUID id,
        String label,
        BigDecimal amount,
        LocalDate issuedAt,
        boolean hasPhoto,
        int sortOrder
) {
    public static WorkActReceiptResponse from(WorkActReceipt r) {
        return new WorkActReceiptResponse(r.getId(), r.getLabel(), r.getAmount(), r.getIssuedAt(),
                r.getStorageKey() != null, r.getSortOrder());
    }
}
