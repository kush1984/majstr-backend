package com.majstr.backend.service;

import com.majstr.backend.service.PaymentReceiptReader.Read;
import com.majstr.backend.service.PaymentReceiptReader.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision that writes a client's payment into the master's object without his tap (V217) — so
 * every way a receipt can fail to back the notice is pinned, with the reason the master reads.
 */
class PaymentReceiptCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final String IBAN = "UA213223130000026007233566001";
    private static final String CARD = "4111111111111111";
    private static final BigDecimal CLAIMED = new BigDecimal("10000");

    private static Read receipt(String amount, Status status, LocalDate date, String iban, String last4) {
        return new Read(amount == null ? null : new BigDecimal(amount), "UAH", status, date, iban, last4, "Іван П.");
    }

    private static PaymentReceiptCheck.Outcome check(Read read) {
        return PaymentReceiptCheck.check(read, CLAIMED, IBAN, CARD, TODAY);
    }

    @Test
    void aReceiptToHisCardForTheSumTypedMatches_andIsDatedByTheReceipt() {
        var outcome = check(receipt("10000.00", Status.SUCCESS, TODAY.minusDays(1), null, "1111"));

        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.receivedAt()).isEqualTo(TODAY.minusDays(1));
        assertThat(outcome.note()).isEqualTo(PaymentReceiptCheck.MATCHED);
    }

    @Test
    void hisIbanInFullMatchesToo_andABankThatSaysNothingAboutStatusIsNotRefused() {
        assertThat(check(receipt("10000", Status.UNKNOWN, TODAY, IBAN, null)).matched()).isTrue();
    }

    @Test
    void aDifferentSumIsNamedWithBothFigures() {
        var outcome = check(receipt("9000", Status.SUCCESS, TODAY, IBAN, null));

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.note()).isEqualTo("У квитанції 9 000 ₴, а клієнт вказав 10 000 ₴.");
    }

    @Test
    void someoneElsesCardOrIban_isAnotherRecipient() {
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY, null, "9999")).note())
                .isEqualTo("У квитанції інший отримувач, ніж ваші реквізити.");
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY, "UA903052992990004149123456789", null)).note())
                .isEqualTo("У квитанції інший отримувач, ніж ваші реквізити.");
    }

    @Test
    void noRecipientOnTheReceipt_saysSo() {
        // A payment link's receipt names the payment service, not the master: never a match.
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY, null, null)).note())
                .isEqualTo("У квитанції не видно рахунку чи картки отримувача.");
    }

    @Test
    void aTransferTheBankRefusedOrStillProcesses_isNotRecorded() {
        assertThat(check(receipt("10000", Status.FAILED, TODAY, IBAN, null)).matched()).isFalse();
        assertThat(check(receipt("10000", Status.IN_PROGRESS, TODAY, IBAN, null)).note())
                .isEqualTo("У квитанції переказ ще в обробці.");
    }

    @Test
    void anOldOrFutureOrMissingDate_isNotRecorded() {
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY.minusDays(7), IBAN, null)).matched()).isTrue();
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY.minusDays(8), IBAN, null)).note())
                .isEqualTo("Квитанція від 12.11.2026 — давніша за тиждень.");
        assertThat(check(receipt("10000", Status.SUCCESS, TODAY.plusDays(1), IBAN, null)).matched()).isFalse();
        assertThat(check(receipt("10000", Status.SUCCESS, null, IBAN, null)).note())
                .isEqualTo("У квитанції не видно дати.");
    }

    @Test
    void anUnreadSumOrAForeignCurrency_isNotRecorded() {
        assertThat(check(receipt(null, Status.SUCCESS, TODAY, IBAN, null)).note())
                .isEqualTo("У квитанції не видно суми переказу.");
        Read dollars = new Read(CLAIMED, "USD", Status.SUCCESS, TODAY, IBAN, null, null);
        assertThat(check(dollars).matched()).isFalse();
    }

    @Test
    void aMasterWithOnlyALinkHasNothingToMatch() {
        var outcome = PaymentReceiptCheck.check(receipt("10000", Status.SUCCESS, TODAY, IBAN, "1111"),
                CLAIMED, null, null, TODAY);

        assertThat(outcome.matched()).isFalse();
    }
}
