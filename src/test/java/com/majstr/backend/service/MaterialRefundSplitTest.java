package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client's material money, on its own, because three screens read it and none of them may
 * re-derive it. Money given for material is material — before the purchase or after it — and never
 * pays for the work.
 */
class MaterialRefundSplitTest {

    /** 2 000 ₴ back for a 2 000 ₴ till receipt: settled, and the work axis untouched. */
    @Test
    void aRefundThatMatchesTheReceivableClearsIt() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("2000"), money("2000"));

        assertThat(split.applied()).isEqualByComparingTo("2000");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("0");
        assertThat(split.workPaid(money("7000"))).isEqualByComparingTo("5000");
    }

    /** A part payment settles part of the material; the rest is still owed, and still asked for. */
    @Test
    void aPartialRefundLeavesTheRestOutstanding() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("800"), money("2000"));

        assertThat(split.applied()).isEqualByComparingTo("800");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("1200");
    }

    /**
     * Money for material handed over BEFORE the purchase. It used to become work money above the
     * receivable (the B-65 cap); now it is the client's material balance and the work axis stays
     * where it was.
     */
    @Test
    void moneyForMaterialAboveTheReceiptsIsTheClientsBalance() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("3000"), money("1000"));

        assertThat(split.applied()).isEqualByComparingTo("3000");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("-2000");
        assertThat(split.workPaid(money("10000"))).isEqualByComparingTo("7000");
    }

    /** A receivable with no refund against it changes nothing about the work axis. */
    @Test
    void noRefundLeavesTheWorkAxisAlone() {
        MaterialRefundSplit split = MaterialRefundSplit.of(BigDecimal.ZERO, money("2000"));

        assertThat(split.applied()).isEqualByComparingTo("0");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("2000");
        assertThat(split.workPaid(money("5000"))).isEqualByComparingTo("5000");
    }

    /**
     * The owner's object «Тест Б»: 5 000 ₴ given for material, 763,72 ₴ of till receipts, then an act
     * re-billing 16 388 ₴ of receipts. What is left of the material money pays that act — the client's
     * balance closes and the works axis counts it as received.
     */
    @Test
    void whatIsLeftOfTheMaterialMoneyPaysForTheMaterialOnSignedActs() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("5000"), money("763.72"), money("16388"));

        assertThat(split.toActs()).isEqualByComparingTo("4236.28");
        assertThat(split.applied()).isEqualByComparingTo("763.72");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("0");
        // 5 000 arrived, 763,72 of it bought till material — the rest is the act's.
        assertThat(split.workPaid(money("5000"))).isEqualByComparingTo("4236.28");
    }

    /** The act's material is smaller than what is left: the act is covered, the rest stays his. */
    @Test
    void anActSmallerThanTheBalanceTakesOnlyItsOwnAmount() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("5000"), money("1000"), money("1500"));

        assertThat(split.toActs()).isEqualByComparingTo("1500");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("-2500");
    }

    /** Till receipts come first: money that does not even cover them leaves nothing for an act. */
    @Test
    void tillReceiptsAreCoveredBeforeAnyAct() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("800"), money("2000"), money("5000"));

        assertThat(split.toActs()).isEqualByComparingTo("0");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("1200");
        assertThat(split.workPaid(money("800"))).isEqualByComparingTo("0");
    }

    /** The sums are COALESCE'd in SQL today; a null must still never reach the arithmetic. */
    @Test
    void nullsReadAsZero() {
        MaterialRefundSplit split = MaterialRefundSplit.of(null, null);

        assertThat(split.refunds()).isEqualByComparingTo("0");
        assertThat(split.reimbursable()).isEqualByComparingTo("0");
        assertThat(split.applied()).isEqualByComparingTo("0");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("0");
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
