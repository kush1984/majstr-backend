package com.majstr.backend.service;

import java.math.BigDecimal;

/**
 * The client's MATERIAL money on one object, kept apart from the money for the work
 * (object-money iteration; replaces the B-65 cap).
 *
 * <p>Two kinds of row feed it. A {@code payment_receipt} ticked {@code materialRefund} is money the
 * client handed over FOR material — paid back after the master bought it, or handed over BEFORE he
 * did. A till receipt marked «клієнт поверне» is material the master bought for the client. Neither
 * is the master's money: the screens keep both out of «Прийшло»/«Витрачено» and show the balance on
 * its own line.</p>
 *
 * <p>B-65 capped the refund at what was owed and called the surplus work money. That made the one
 * case the master named himself — «клієнт дав гроші на матеріал наперед» — pay off work before a
 * single receipt existed. So material money stays material: it covers the till receipts first,
 * and what is left of it covers the MATERIAL billed through signed acts ({@code toActs}) — receipts
 * on an act are material too, bought with the master's money and re-billed to the client. Only what
 * covers neither is still the client's, unspent: a NEGATIVE balance, «лишилось з грошей клієнта».
 * The owner's case (2026-11-03): 5 000 ₴ given, 763,72 ₴ of till receipts, then an act billing
 * 16 388 ₴ of receipts — the 4 236,28 ₴ left must pay that act, not sit beside a debt of 20 138 ₴.</p>
 *
 * <p>Computed, never stored: the order in which the money, the receipts and the acts arrived does
 * not matter, and a reopened act takes its share back by itself.</p>
 *
 * @param refunds      Σ of the object's payments ticked «на матеріал»
 * @param reimbursable Σ of the till receipts marked «клієнт поверне» and not billed on an act — the
 *                     same figure the materials axis counts
 * @param actMaterials Σ of the receipts on the object's SIGNED acts (paid less returned)
 * @param toActs       the material money that paid for {@code actMaterials}: what is left of
 *                     {@code refunds} once the till receipts are covered, at most {@code actMaterials}.
 *                     It is payment for an act, so it counts as received on the works axis and as
 *                     the master's income
 * @param applied      the material money kept OFF the works axis — {@code refunds − toActs}
 * @param materialsOutstanding {@code reimbursable − applied}: positive = the client still owes for
 *                     material, negative = his money the master has not spent yet
 */
public record MaterialRefundSplit(
        BigDecimal refunds,
        BigDecimal reimbursable,
        BigDecimal actMaterials,
        BigDecimal toActs,
        BigDecimal applied,
        BigDecimal materialsOutstanding
) {

    public static MaterialRefundSplit of(BigDecimal refunds, BigDecimal reimbursable, BigDecimal actMaterials) {
        BigDecimal paid = orZero(refunds);
        BigDecimal owed = orZero(reimbursable);
        BigDecimal acts = orZero(actMaterials).max(BigDecimal.ZERO);
        BigDecimal spare = paid.subtract(owed).max(BigDecimal.ZERO);
        BigDecimal toActs = spare.min(acts);
        BigDecimal applied = paid.subtract(toActs);
        return new MaterialRefundSplit(paid, owed, acts, toActs, applied, owed.subtract(applied));
    }

    /** An object with no material on signed acts. */
    public static MaterialRefundSplit of(BigDecimal refunds, BigDecimal reimbursable) {
        return of(refunds, reimbursable, BigDecimal.ZERO);
    }

    /** What of «Отримано» was payment for the WORK and the acts — the only part «За договором» may be
     *  measured against. {@code received} stays gross everywhere it is shown: the money really did
     *  arrive, and a total the itemized rows do not add up to is its own bug. */
    public BigDecimal workPaid(BigDecimal received) {
        return received.subtract(applied);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
