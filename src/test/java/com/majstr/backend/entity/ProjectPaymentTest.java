package com.majstr.backend.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPaymentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    private ProjectPayment payment(String amount, LocalDate dueDate) {
        return ProjectPayment.builder().amount(new BigDecimal(amount)).dueDate(dueDate).build();
    }

    private static BigDecimal received(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Test
    void noPaymentYet_noDueDate_isPlanned() {
        assertThat(payment("1000", null).status(TODAY, received(null))).isEqualTo(ProjectPaymentStatus.PLANNED);
    }

    @Test
    void noPaymentYet_futureDueDate_isStillPlanned() {
        assertThat(payment("1000", TODAY.plusDays(5)).status(TODAY, received(null)))
                .isEqualTo(ProjectPaymentStatus.PLANNED);
    }

    @Test
    void noPaymentYet_pastDueDate_isOverdue() {
        assertThat(payment("1000", TODAY.minusDays(1)).status(TODAY, received(null)))
                .isEqualTo(ProjectPaymentStatus.OVERDUE);
    }

    @Test
    void partialPayment_isPartial_evenPastDueDate() {
        // A partial payment already came in — OVERDUE would be a worse (and less honest) signal
        // than PARTIAL, which is exactly what happened.
        assertThat(payment("1000", TODAY.minusDays(1)).status(TODAY, received("400")))
                .isEqualTo(ProjectPaymentStatus.PARTIAL);
    }

    @Test
    void fullPayment_isReceived() {
        assertThat(payment("1000", null).status(TODAY, received("1000"))).isEqualTo(ProjectPaymentStatus.RECEIVED);
    }

    @Test
    void overpayment_isStillReceived_notSomethingElse() {
        assertThat(payment("1000", null).status(TODAY, received("1200"))).isEqualTo(ProjectPaymentStatus.RECEIVED);
    }

    @Test
    void zeroReceived_isTreatedAsNotYetPaid() {
        // received = 0 (explicit, e.g. Σ of zero receipts) must behave like null, not partial.
        assertThat(payment("1000", TODAY.plusDays(3)).status(TODAY, received("0")))
                .isEqualTo(ProjectPaymentStatus.PLANNED);
    }
}
