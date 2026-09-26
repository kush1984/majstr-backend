package com.majstr.backend.service;

import java.math.BigDecimal;

/**
 * Where a «повернення за матеріал» actually lands (review B-65).
 *
 * <p>A {@code payment_receipt} ticked {@code materialRefund} is money the client handed back for
 * material the master bought at the till. Until this existed, every screen simply added it to
 * «Отримано» and subtracted it from «За договором» — so a 2 000 ₴ refund paid off 2 000 ₴ of WORK
 * that nobody had paid for. Reproduced on live figures: contract 11 800, work paid 5 000, a 2 000
 * refund → «залишок 4 800» when the client still owed 6 800, the materials card still asking for
 * the 2 000 it had just been given, and «Усе сплачено ✓» arriving with 2 000 of work unpaid.</p>
 *
 * <p>The rule is one line: a refund pays off MATERIAL first, and only what there is material to pay
 * off. {@code applied = min(Σ refunds, reimbursable)} — the cap is what makes the two axes add up,
 * because anything above it has no receivable to settle and is therefore payment for work like any
 * other. Nothing is clamped silently: the surplus stays inside {@code workPaid}, where it becomes
 * an overpayment the master can see rather than a figure that quietly went missing.</p>
 *
 * @param refunds     Σ of the object's receipts ticked «повернення за матеріал»
 * @param reimbursable Σ of the till receipts still marked «клієнт відшкодовує» — the same figure the
 *                     materials axis shows, so the two can never disagree about what is owed
 * @param applied     how much of the refund a receivable actually absorbed
 * @param materialsOutstanding what the client still owes for material, after the refund
 */
public record MaterialRefundSplit(
        BigDecimal refunds,
        BigDecimal reimbursable,
        BigDecimal applied,
        BigDecimal materialsOutstanding
) {

    public static MaterialRefundSplit of(BigDecimal refunds, BigDecimal reimbursable) {
        BigDecimal paid = refunds == null ? BigDecimal.ZERO : refunds;
        BigDecimal owed = reimbursable == null ? BigDecimal.ZERO : reimbursable;
        BigDecimal applied = paid.min(owed).max(BigDecimal.ZERO);
        return new MaterialRefundSplit(paid, owed, applied, owed.subtract(applied));
    }

    /** What of «Отримано» was payment for the WORK — the only half «За договором» may be measured
     *  against. {@code received} stays gross everywhere it is shown: the money really did arrive,
     *  and a total the itemized rows do not add up to is its own bug. */
    public BigDecimal workPaid(BigDecimal received) {
        return received.subtract(applied);
    }
}
