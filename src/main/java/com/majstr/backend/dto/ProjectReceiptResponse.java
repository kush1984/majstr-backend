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
        /** Whether the own-cost expense this receipt posted still exists. Only ever true while
         *  {@code reimbursable} is false; false beside a false {@code reimbursable} means the row
         *  was removed from the journal before that became impossible, and the receipt claims a cost
         *  the economy does not count. */
        boolean hasExpense,
        boolean duplicate,
        int sortOrder
) {
    public static ProjectReceiptResponse from(ProjectReceipt r, boolean duplicate) {
        return new ProjectReceiptResponse(r.getId(), r.getLabel(), r.getAmount(), r.getIssuedAt(),
                r.getStorageKey() != null, r.isReimbursable(), r.getExpenseId() != null, duplicate,
                r.getSortOrder());
    }
}
