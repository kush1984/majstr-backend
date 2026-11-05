package com.majstr.backend.service;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentReceiptRepository receiptRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectService projectService;
    @Mock UserRepository userRepository;
    @Mock MaterialRefundCalculator refundCalculator;

    // The REAL gate (backed by PlanConfig), same pattern ObjectExpenseServiceTest uses — the
    // PRO/FREE decision is genuinely exercised, not mocked away.
    private final DefaultFeatureGuard featureGuard = new DefaultFeatureGuard();

    private PaymentService service() {
        return new PaymentService(receiptRepository, estimateRepository, refundCalculator,
                projectService, userRepository, featureGuard);
    }

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();

    private Project object() {
        Project p = new Project();
        p.setId(objectId);
        return p;
    }

    private void user(UUID id, Plan plan) {
        given(userRepository.findById(id)).willReturn(Optional.of(User.builder().id(id).plan(plan).build()));
    }

    /** A stage of the removed payment plan — what a receipt recorded before 2026-11-05 may point at. */
    private ProjectPayment oldStage(String purpose) {
        return ProjectPayment.builder().id(UUID.randomUUID()).project(object()).purpose(purpose).build();
    }

    private void savesAsGiven() {
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    private static PaymentReceiptRequest receipt(String label, String amount, boolean materialRefund) {
        return new PaymentReceiptRequest(label, new BigDecimal(amount), LocalDate.now(), materialRefund);
    }

    // ---- summary -------------------------------------------------------------

    @Test
    void summaryUnchecked_computesRemainingAsContractedMinusReceived_clampedAtZero() {
        contracted("1000.00");
        refunds("0", "0");
        received("1500.00");

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.contractedTotal()).isEqualByComparingTo("1000.00");
        assertThat(summary.received()).isEqualByComparingTo("1500.00");
        assertThat(summary.remaining()).isEqualByComparingTo("0.00"); // clamped, not negative
        assertThat(summary.overpaid()).isEqualByComparingTo("500.00"); // and SAID, not swallowed
        assertThat(summary.receipts()).hasSize(1);
    }

    @Test
    void summary_listsAReceiptOnAnOldStageUnderTheStagesName() {
        contracted("1000.00");
        refunds("0", "0");
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)).willReturn(List.of(
                PaymentReceipt.builder().id(UUID.randomUUID()).project(object()).planPayment(oldStage("Завдаток"))
                        .amount(new BigDecimal("400.00")).receivedAt(LocalDate.now()).build(),
                PaymentReceipt.builder().id(UUID.randomUUID()).project(object()).label("Друга частина")
                        .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).build()));

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.receipts()).extracting(PaymentReceiptResponse::displayLabel)
                .containsExactly("Завдаток", "Друга частина");
        assertThat(summary.received()).isEqualByComparingTo("700.00");
    }

    /**
     * An app build up to 1.68 is still cached on phones: its payments block runs
     * {@code summary.payments.forEach} and lists {@code unplannedReceipts}. With no stages it must
     * get an empty plan and every payment as its own row — never a missing list, which crashes it.
     */
    @Test
    void summary_stillAnswersTheShapeAnOldBuildReads() {
        contracted("1000.00");
        refunds("0", "0");
        UUID stageId = UUID.randomUUID();
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)).willReturn(List.of(
                PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                        .planPayment(ProjectPayment.builder().id(stageId).project(object()).purpose("Завдаток").build())
                        .amount(new BigDecimal("400.00")).receivedAt(LocalDate.now()).build()));

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.payments()).isEmpty();
        assertThat(summary.unplannedReceipts()).isEqualTo(summary.receipts());
        // …and it still sees which payment sat on a stage: that is how it knows not to offer the
        // refund tick or a rename, both of which the server refuses on such a row.
        assertThat(summary.receipts()).singleElement()
                .satisfies(r -> assertThat(r.planPaymentId()).isEqualTo(stageId));
    }

    // ---- B-65: a refund pays off MATERIAL, never work ------------------------

    /**
     * The reproduction from the review: contract 11 800, work paid 5 000, and a 2 000 ₴ refund for a
     * 2 000 ₴ till receipt. Measuring «За договором» against the GROSS 7 000 read «залишилось 4 800»
     * and, after 4 800 more, «Усе сплачено ✓» with 2 000 ₴ of work never paid for.
     */
    @Test
    void aRefundSettlesTheMaterialsAxisAndLeavesTheWorkOutstanding() {
        contracted("11800.00");
        refunds("2000.00", "2000.00");
        received("5000.00", "2000.00");

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.received()).isEqualByComparingTo("7000.00"); // the till really saw it
        assertThat(summary.refundApplied()).isEqualByComparingTo("2000.00");
        assertThat(summary.workPaid()).isEqualByComparingTo("5000.00");
        assertThat(summary.remaining()).isEqualByComparingTo("6800.00");
        assertThat(summary.overpaid()).isEqualByComparingTo("0.00");
    }

    /**
     * Money handed over for material BEFORE the receipts exist (object-money iteration). It stays
     * the client's material money in full — the B-65 cap made the surplus pay off work nobody had
     * done yet.
     */
    @Test
    void moneyForMaterialBeyondTheReceiptsNeverPaysForWork() {
        contracted("10000.00");
        refunds("3000.00", "1000.00");
        received("10000.00", "3000.00");

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.materialRefunds()).isEqualByComparingTo("3000.00");
        assertThat(summary.refundApplied()).isEqualByComparingTo("3000.00");
        assertThat(summary.workPaid()).isEqualByComparingTo("10000.00");
        assertThat(summary.remaining()).isEqualByComparingTo("0.00");
        assertThat(summary.overpaid()).isEqualByComparingTo("0.00");
    }

    @Test
    void summary_staysReachableOnFree_onlyMutationsAreGated() {
        // No user() stub at all — if this called requireEconomy, the un-stubbed
        // userRepository.findById would 404 instead of returning data.
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        contracted("0");
        refunds("0", "0");
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)).willReturn(List.of());

        assertThat(service().summary(objectId, ownerId).contractedTotal()).isEqualByComparingTo("0");
        verify(userRepository, never()).findById(any());
    }

    private void contracted(String amount) {
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal(amount));
    }

    private void refunds(String paid, String reimbursable) {
        given(refundCalculator.forObject(objectId)).willReturn(
                MaterialRefundSplit.of(new BigDecimal(paid), new BigDecimal(reimbursable)));
    }

    private void received(String... amounts) {
        List<PaymentReceipt> receipts = new ArrayList<>();
        for (String amount : amounts) {
            receipts.add(PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                    .amount(new BigDecimal(amount)).receivedAt(LocalDate.now()).label("Своє").build());
        }
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId))
                .willReturn(receipts);
    }

    // ---- addReceipt ------------------------------------------------------------

    @Test
    void addReceipt_savesTheLabelledPayment() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        savesAsGiven();

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                receipt("  Продаж інструменту ", "300.00", false), null);

        assertThat(saved).singleElement().satisfies(r -> {
            assertThat(r.label()).isEqualTo("Продаж інструменту");
            assertThat(r.amount()).isEqualByComparingTo("300.00");
        });
    }

    @Test
    void addReceipt_requiresANonBlankLabel() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId, receipt("  ", "300.00", false), null))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessage("error.payment.label-required");
        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    @Test
    void addReceipt_replayedWithSameClientId_doesNotDuplicate() {
        UUID clientId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findById(clientId)).willReturn(Optional.of(PaymentReceipt.builder()
                .id(clientId).project(object()).label("Аванс")
                .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).build()));

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                receipt("Аванс", "300.00", false), clientId);

        assertThat(saved).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(clientId));
        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    @Test
    void addReceipt_aClientIdTakenOnAnotherObject_isRefused() {
        UUID clientId = UUID.randomUUID();
        Project other = new Project();
        other.setId(UUID.randomUUID());
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findById(clientId)).willReturn(Optional.of(PaymentReceipt.builder()
                .id(clientId).project(other).label("Аванс")
                .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).build()));

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId, receipt("Аванс", "300.00", false), clientId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addReceipt_allowsFreeToo_temporarily() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: economy
        // opened up to FREE while the AI-calling flows are hidden to cut AI spend.
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        savesAsGiven();

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                receipt("Щось", "10", false), null);

        assertThat(saved).singleElement().satisfies(r -> assertThat(r.displayLabel()).isEqualTo("Щось"));
    }

    // ---- edit / delete ------------------------------------------------------------

    @Test
    void editReceipt_onAnOldStage_keepsTheStagesNameAndNeedsNoLabel() {
        UUID receiptId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findByIdAndProjectId(receiptId, objectId)).willReturn(Optional.of(
                PaymentReceipt.builder().id(receiptId).project(object()).planPayment(oldStage("Завдаток"))
                        .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now().minusDays(1)).build()));

        PaymentReceiptResponse resp = service().editReceipt(objectId, receiptId, ownerId,
                new PaymentReceiptEditRequest(new BigDecimal("350.00"), LocalDate.now(), null, false));

        assertThat(resp.amount()).isEqualByComparingTo("350.00");
        assertThat(resp.displayLabel()).isEqualTo("Завдаток");
    }

    /** B-102: a material refund pays for no work, so a receipt on an old stage is never one. */
    @Test
    void editReceipt_aMaterialRefundOnAnOldStage_isRefused() {
        UUID receiptId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findByIdAndProjectId(receiptId, objectId)).willReturn(Optional.of(
                PaymentReceipt.builder().id(receiptId).project(object()).planPayment(oldStage("Завдаток"))
                        .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).build()));

        assertThatThrownBy(() -> service().editReceipt(objectId, receiptId, ownerId,
                new PaymentReceiptEditRequest(new BigDecimal("300.00"), LocalDate.now(), null, true)))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessage("error.payment.refund-on-stage");
    }

    @Test
    void editReceipt_absentMaterialRefundLeavesTheFlagAlone() {
        // The object economy's edit sheet has no refund switch and sends no such field. Read as a
        // primitive it arrived false and quietly cleared a flag «Мої гроші» had set — moving that
        // screen's «Заробив» because the master retyped an amount on a different screen.
        UUID receiptId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        PaymentReceipt stored = PaymentReceipt.builder().id(receiptId).project(object()).label("На плитку")
                .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).materialRefund(true).build();
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findByIdAndProjectId(receiptId, objectId)).willReturn(Optional.of(stored));

        service().editReceipt(objectId, receiptId, ownerId,
                new PaymentReceiptEditRequest(new BigDecimal("350.00"), LocalDate.now(), "На плитку", null));

        assertThat(stored.isMaterialRefund()).isTrue();

        // …and the caller that DOES own the switch still turns it off.
        service().editReceipt(objectId, receiptId, ownerId,
                new PaymentReceiptEditRequest(new BigDecimal("350.00"), LocalDate.now(), "На плитку", false));

        assertThat(stored.isMaterialRefund()).isFalse();
    }

    @Test
    void deleteReceipt_alreadyGoneIsANoOp() {
        UUID receiptId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findByIdAndProjectId(receiptId, objectId)).willReturn(Optional.empty());

        service().deleteReceipt(objectId, receiptId, ownerId); // must not throw

        verify(receiptRepository, never()).delete(any(PaymentReceipt.class));
    }
}
