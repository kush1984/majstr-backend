package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectReceipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One receipt filed against an object (V129). {@code hasPhoto} tells the client whether a file
 * endpoint exists for it — the storage key itself is never exposed.
 *
 * <p>{@code duplicate} is a computed read-path warning, never a stored fact: an earlier receipt on
 * this object carries the same printed fiscal identity, so this is very probably the same paper
 * photographed twice. Nothing is blocked — the master decides, because a shop can legitimately
 * reprint a slip and only he is holding it.</p>
 */
public record ProjectReceiptResponse(
        UUID id,
        String label,
        BigDecimal amount,
        LocalDate issuedAt,
        boolean hasPhoto,
        /** false = «клієнт відшкодовує» is off and this receipt is the master's own cost, posted as
         *  a MATERIALS/RECEIPT expense. True (the default) means it is a receivable and touches no
         *  money in the economy's internals. */
        boolean reimbursable,
        boolean duplicate,
        int sortOrder
) {
    public static ProjectReceiptResponse from(ProjectReceipt r, boolean duplicate) {
        return new ProjectReceiptResponse(r.getId(), r.getLabel(), r.getAmount(), r.getIssuedAt(),
                r.getStorageKey() != null, r.isReimbursable(), duplicate, r.getSortOrder());
    }
}
