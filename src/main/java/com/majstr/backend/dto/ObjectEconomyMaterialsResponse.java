package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * The materials axis of the object economy (V129) — «клієнт відшкодовує за матеріал». Computed
 * <b>unconditionally</b>, so it sits on the FREE-visible side beside the estimates and the works
 * axis.
 *
 * <p><b>This is a receivable, not income and not a cost.</b> It is deliberately NOT folded into
 * «За договором» / «Прийнято актами»: those two count one estimate set and the ⊆ invariant between
 * them must hold, while a receipt at the till belongs to no signed document at all. Money for
 * material enters the contract only when an act picks the receipt up — and then it arrives through
 * {@code ActAddendumCreator} like every other act receipt.</p>
 *
 * <p>The receipts the master flipped to «це моя витрата» are absent here on purpose: those are
 * {@code object_expenses} rows and are counted in the (PRO) internals, never twice.</p>
 *
 * @param reimbursable  Σ of the object's receipts still marked «клієнт відшкодовує»
 * @param receiptCount  how many receipts that is — a sum with no count reads as a mystery
 * @param unpricedCount how many receipts are saved but still carry no amount, so the master knows
 *                      the figure above is not yet the whole story
 */
public record ObjectEconomyMaterialsResponse(
        BigDecimal reimbursable,
        long receiptCount,
        long unpricedCount
) {}
