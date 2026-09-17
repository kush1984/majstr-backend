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
        /** Part of the receipt returned to the shop (V115); {@code amount} stays what the paper says,
         *  so the client can check the photo against it. What is billed is {@link #billedAmount()}. */
        BigDecimal returnedAmount,
        LocalDate issuedAt,
        boolean hasPhoto,
        /** The positions were carried into the act (round 2) — the amount is shown as reference but
         *  excluded from «Разом за чеками»/payable: the act's own lines already bill it. */
        boolean itemized,
        /** The same paper filed against the object as well, or null (B-04). A warning in both
         *  directions: the object's list points here, this points back. */
        ReceiptDuplicateRef duplicateOf,
        int sortOrder
) {
    /** No twin known — every path that has not looked across the two tables answers with this. */
    public static WorkActReceiptResponse from(WorkActReceipt r) {
        return from(r, null);
    }

    public static WorkActReceiptResponse from(WorkActReceipt r, ReceiptDuplicateRef duplicateOf) {
        return new WorkActReceiptResponse(r.getId(), r.getLabel(), r.getAmount(), r.getReturnedAmount(),
                r.getIssuedAt(), r.getStorageKey() != null, r.isItemized(), duplicateOf,
                r.getSortOrder());
    }

    /** Paid less returned — the figure that reaches «Разом за чеками», the ADDENDUM and the expense. */
    public BigDecimal billedAmount() {
        return amount.subtract(returnedAmount == null ? BigDecimal.ZERO : returnedAmount);
    }
}
