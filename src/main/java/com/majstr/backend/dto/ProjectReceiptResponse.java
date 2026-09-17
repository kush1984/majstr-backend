package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectReceipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One receipt filed against an object (V129). {@code hasPhoto} tells the client whether a file
 * endpoint exists for it — the storage key itself is never exposed.
 *
 * <p>{@code duplicateOf} is a computed read-path warning, never a stored fact: another receipt
 * carries the same printed fiscal identity, so this is very probably the same paper filed twice. It
 * names WHERE the twin is, because since B-04 it can be in the other table — a receipt attached to
 * an act. Nothing is blocked; the master decides.</p>
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
        /** The same paper, filed somewhere else — or null, which is the ordinary case. */
        ReceiptDuplicateRef duplicateOf,
        /** The SIGNED act that billed this paper to the client (B-04). Stamped automatically when an
         *  act carrying the same fiscal identity is signed; while it is set the receipt has left the
         *  «клієнт відшкодовує» receivable — the act's ADDENDUM already moved that money into «За
         *  договором», and showing it in both places would bill the client twice on screen. */
        UUID billedOnActId,
        String billedOnActNumber,
        int sortOrder
) {
    /** No twin known and not billed on any act — what a freshly created receipt always is. */
    public static ProjectReceiptResponse from(ProjectReceipt r) {
        return from(r, null, null);
    }

    public static ProjectReceiptResponse from(ProjectReceipt r, ReceiptDuplicateRef duplicateOf,
                                              String billedOnActNumber) {
        return new ProjectReceiptResponse(r.getId(), r.getLabel(), r.getAmount(), r.getIssuedAt(),
                r.getStorageKey() != null, r.isReimbursable(), r.getExpenseId() != null, duplicateOf,
                r.getBilledOnActId(), billedOnActNumber, r.getSortOrder());
    }
}
