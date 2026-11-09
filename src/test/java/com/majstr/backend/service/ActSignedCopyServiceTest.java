package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.repository.EstimateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The «ДОВІДКОВО» figure the client's emailed copy is built with (review item B-12).
 *
 * <p>This one arithmetic line had drifted away from every other receipt total in the codebase: it
 * summed gross {@code amount()} over every row, where {@code WorkActReceiptRepository.sumByWorkActId}
 * — and the PDF, and the ADDENDUM, and {@code payable} — sum {@code billedAmount()} (V115) and skip
 * the legacy {@code itemized} rows whose money is already in the act lines. So it double-counted an
 * itemized receipt and billed back a partial return.</p>
 *
 * <p>The value is dead for a SIGNED act — the calculator ignores the act's own receipts there, and
 * both callers stamp SIGNED first — which is precisely why nobody noticed, and precisely why it is
 * pinned here instead of left to be found the day a caller passes an unsigned act.</p>
 */
class ActSignedCopyServiceTest {

    @Test
    void aPartialReturnIsNotBilledBack() {
        assertThat(ActSignedCopyService.receiptsTotal(List.of(
                receipt("483.50", "100.00", false))))
                .isEqualByComparingTo("383.50");
    }

    /** Legacy {@code itemized}: its positions are act lines already, so counting it bills twice. */
    @Test
    void anItemizedReceiptIsNotCountedAtAll() {
        assertThat(ActSignedCopyService.receiptsTotal(List.of(
                receipt("483.50", "0.00", true))))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void theRestIsSummed() {
        assertThat(ActSignedCopyService.receiptsTotal(List.of(
                receipt("483.50", "100.00", false),
                receipt("1000.00", "0.00", true),
                receipt("16.50", null, false))))
                .isEqualByComparingTo("400.00");
    }

    @Test
    void noReceiptsIsZeroAndNotNull() {
        assertThat(ActSignedCopyService.receiptsTotal(List.of())).isEqualByComparingTo("0");
    }

    // ---- the client's copy is OUR letter (share-without-verification) -------------------------

    @Test
    void anUnverifiedMastersOfflineSignature_freezesButHoldsTheCopyBack() {
        AfterCommit afterCommit = mock(AfterCommit.class);
        ActSignedCopyService service = service(afterCommit);

        boolean withheld = service.freezeAndSend(act("klient@example.com"), List.of(), List.of(), false);

        assertThat(withheld).isTrue();
        verify(afterCommit, never()).run(any());
    }

    @Test
    void aVerifiedMaster_mailsTheCopy() {
        AfterCommit afterCommit = mock(AfterCommit.class);
        ActSignedCopyService service = service(afterCommit);

        boolean withheld = service.freezeAndSend(act("klient@example.com"), List.of(), List.of(), true);

        assertThat(withheld).isFalse();
        verify(afterCommit).run(any());
    }

    @Test
    void noClientEmail_isNothingWithheld() {
        AfterCommit afterCommit = mock(AfterCommit.class);
        ActSignedCopyService service = service(afterCommit);

        assertThat(service.freezeAndSend(act(null), List.of(), List.of(), false)).isFalse();
        verify(afterCommit, never()).run(any());
    }

    private static ActSignedCopyService service(AfterCommit afterCommit) {
        SignedDocumentService signed = mock(SignedDocumentService.class);
        given(signed.freezeAct(any(), any(), any(), any(), any()))
                .willReturn(new SignedDocumentService.Frozen("a".repeat(64), new byte[] {1}));
        return new ActSignedCopyService(mock(ActCumulativeCalculator.class), mock(EstimateRepository.class),
                mock(EmailService.class), afterCommit, signed);
    }

    private static WorkAct act(String clientEmail) {
        User owner = User.builder().fullName("Майстер").build();
        Client client = Client.builder().fullName("Олена").email(clientEmail).build();
        Project project = Project.builder().owner(owner).client(client).name("Обʼєкт").build();
        return WorkAct.builder().project(project).number("3").build();
    }

    private static WorkActPdfService.ReceiptRow receipt(String amount, String returned,
                                                        boolean itemized) {
        return new WorkActPdfService.ReceiptRow("Епіцентр", LocalDate.of(2026, 9, 8),
                new BigDecimal(amount), returned == null ? null : new BigDecimal(returned),
                "receipts/1.jpg", itemized);
    }
}
