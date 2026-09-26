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
 * <p><b>A refund the client already handed over leaves this axis (review B-65).</b> Until then the
 * card went on asking for material that had been paid for minutes earlier, while the very same
 * money was busy paying off work nobody had paid for on the other axis. {@code reimbursable} stays
 * the gross figure the receipts add up to; {@code outstanding} is what is actually still owed.</p>
 *
 * @param reimbursable  Σ of the object's receipts still marked «клієнт відшкодовує»
 * @param refundApplied how much of that a «повернення за матеріал» payment has already settled —
 *                      capped at {@code reimbursable}, see {@code MaterialRefundSplit}
 * @param outstanding   {@code reimbursable − refundApplied}: what the client still owes for material
 * @param receiptCount  how many receipts that is — a sum with no count reads as a mystery
 * @param unpricedCount how many receipts are saved but still carry no amount, so the master knows
 *                      the figure above is not yet the whole story
 */
public record ObjectEconomyMaterialsResponse(
        BigDecimal reimbursable,
        BigDecimal refundApplied,
        BigDecimal outstanding,
        long receiptCount,
        long unpricedCount
) {}
