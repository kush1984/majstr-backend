package com.majstr.backend.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPaymentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    private ProjectPayment payment(String amount, String paidAmount, LocalDate dueDate) {
        return ProjectPayment.builder()
                .amount(new BigDecimal(amount))
                .paidAmount(paidAmount == null ? null : new BigDecimal(paidAmount))
                .dueDate(dueDate)
                .build();
    }

    @Test
    void noPaymentYet_noDueDate_isPlanned() {
        assertThat(payment("1000", null, null).status(TODAY)).isEqualTo(ProjectPaymentStatus.PLANNED);
    }

    @Test
    void noPaymentYet_futureDueDate_isStillPlanned() {
        assertThat(payment("1000", null, TODAY.plusDays(5)).status(TODAY)).isEqualTo(ProjectPaymentStatus.PLANNED);
    }

    @Test
    void noPaymentYet_pastDueDate_isOverdue() {
        assertThat(payment("1000", null, TODAY.minusDays(1)).status(TODAY)).isEqualTo(ProjectPaymentStatus.OVERDUE);
    }

    @Test
    void partialPayment_isPartial_evenPastDueDate() {
        // A partial payment already came in — OVERDUE would be a worse (and less honest) signal
        // than PARTIAL, which is exactly what happened.
        assertThat(payment("1000", "400", TODAY.minusDays(1)).status(TODAY)).isEqualTo(ProjectPaymentStatus.PARTIAL);
    }

    @Test
    void fullPayment_isReceived() {
        assertThat(payment("1000", "1000", null).status(TODAY)).isEqualTo(ProjectPaymentStatus.RECEIVED);
    }

    @Test
    void overpayment_isStillReceived_notSomethingElse() {
        assertThat(payment("1000", "1200", null).status(TODAY)).isEqualTo(ProjectPaymentStatus.RECEIVED);
    }

    @Test
    void zeroPaidAmount_isTreatedAsNotYetPaid() {
        // paidAmount = 0 (explicit) must behave like null, not like a partial payment.
        assertThat(payment("1000", "0", TODAY.plusDays(3)).status(TODAY)).isEqualTo(ProjectPaymentStatus.PLANNED);
    }
}
