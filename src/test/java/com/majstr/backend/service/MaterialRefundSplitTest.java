package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The B-65 formula, on its own, because three screens read it and none of them may re-derive it.
 *
 * <p>A refund pays off MATERIAL. Everything here follows from that one sentence: it may settle at
 * most what the object is actually owed for material, and whatever is left over never had a
 * receivable to settle, so it is work money like any other payment.</p>
 */
class MaterialRefundSplitTest {

    /** The review's own reproduction: 2 000 ₴ back for a 2 000 ₴ till receipt. */
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
     * The cap. Without it a mistyped reimbursement would quietly re-open a contract that is paid,
     * and the materials card would go on to ask for a negative amount.
     */
    @Test
    void aRefundAboveTheReceivableIsWorkMoney() {
        MaterialRefundSplit split = MaterialRefundSplit.of(money("3000"), money("1000"));

        assertThat(split.applied()).isEqualByComparingTo("1000");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("0");
        // The surplus 2 000 stays in workPaid — it had no material left to pay off.
        assertThat(split.workPaid(money("10000"))).isEqualByComparingTo("9000");
    }

    /** A receivable with no refund against it changes nothing about the work axis. */
    @Test
    void noRefundLeavesTheWorkAxisAlone() {
        MaterialRefundSplit split = MaterialRefundSplit.of(BigDecimal.ZERO, money("2000"));

        assertThat(split.applied()).isEqualByComparingTo("0");
        assertThat(split.materialsOutstanding()).isEqualByComparingTo("2000");
        assertThat(split.workPaid(money("5000"))).isEqualByComparingTo("5000");
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
