package com.majstr.backend.service;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentSplitPreviewResponse;
import com.majstr.backend.dto.PaymentSplitRequest;
import com.majstr.backend.dto.PaymentSurplusTransferRequest;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.PaymentOverflowResolution;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.PaymentSplitPreset;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentSplitException;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectPaymentRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Mock ProjectPaymentRepository paymentRepository;
    @Mock PaymentReceiptRepository receiptRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectService projectService;
    @Mock UserRepository userRepository;

    // The REAL gate (backed by PlanConfig), same pattern ObjectExpenseServiceTest uses — the
    // PRO/FREE decision is genuinely exercised, not mocked away.
    private final DefaultFeatureGuard featureGuard = new DefaultFeatureGuard();

    private PaymentService service() {
        return new PaymentService(paymentRepository, receiptRepository, estimateRepository, projectService,
                userRepository, featureGuard);
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

    private ProjectPayment stage(UUID id, BigDecimal amount, String purpose) {
        return ProjectPayment.builder().id(id).project(object()).amount(amount).purpose(purpose).sortOrder(0).build();
    }

    private static ProjectPaymentRequest plan(BigDecimal amount, String purpose) {
        return new ProjectPaymentRequest(amount, null, null, purpose);
    }

    // ---- split calculation --------------------------------------------------

    @Test
    void previewSplit_fiftyFifty_lastRowAbsorbsTheRoundingRemainder() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("100.01"));

        PaymentSplitPreviewResponse resp = service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null));

        assertThat(resp.contractedTotal()).isEqualByComparingTo("100.01");
        assertThat(resp.rows()).hasSize(2);
        assertThat(resp.rows().get(0).purpose()).isEqualTo("Аванс");
        assertThat(resp.rows().get(0).amount()).isEqualByComparingTo("50.01"); // 50% of 100.01, HALF_UP
        assertThat(resp.rows().get(1).purpose()).isEqualTo("Фінал");
        assertThat(resp.rows().get(1).amount()).isEqualByComparingTo("50.00"); // absorbs the remainder
        // Σ rows == contracted total exactly.
        BigDecimal sum = resp.rows().stream().map(r -> r.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(resp.contractedTotal());
    }

    @Test
    void previewSplit_threePartPresets_namePurposesAfterChornovi() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("9000.00"));

        PaymentSplitPreviewResponse thirtyFortyThirty = service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.THIRTY_FORTY_THIRTY, null));

        assertThat(thirtyFortyThirty.rows()).extracting(r -> r.purpose())
                .containsExactly("Аванс", "Після чорнових", "Фінал");
        assertThat(thirtyFortyThirty.rows().get(0).amount()).isEqualByComparingTo("2700.00");
        assertThat(thirtyFortyThirty.rows().get(1).amount()).isEqualByComparingTo("3600.00");
        assertThat(thirtyFortyThirty.rows().get(2).amount()).isEqualByComparingTo("2700.00");
    }

    @Test
    void previewSplit_custom_mustSumToExactly100() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());

        assertThatThrownBy(() -> service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.CUSTOM, List.of(new BigDecimal("40"), new BigDecimal("50")))))
                .isInstanceOf(PaymentSplitException.class);
    }

    @Test
    void previewSplit_custom_validPercents_computesRowsInOrder() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("1000.00"));

        PaymentSplitPreviewResponse resp = service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.CUSTOM,
                        List.of(new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("50"))));

        assertThat(resp.rows()).extracting(r -> r.purpose())
                .containsExactly("Аванс", "Проміжний платіж 1", "Фінал");
        assertThat(resp.rows().get(0).amount()).isEqualByComparingTo("200.00");
        assertThat(resp.rows().get(1).amount()).isEqualByComparingTo("300.00");
        assertThat(resp.rows().get(2).amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void commitSplit_persistsExactlyWhatThePreviewComputed() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("1000.00"));
        given(paymentRepository.nextSortOrder(objectId)).willReturn(0);
        given(paymentRepository.save(any(ProjectPayment.class))).willAnswer(inv -> {
            ProjectPayment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            p.setCreatedAt(Instant.now());
            return p;
        });

        List<ProjectPaymentResponse> saved = service().commitSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null));

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(saved.get(0).sortOrder()).isEqualTo(0);
        assertThat(saved.get(1).sortOrder()).isEqualTo(1);
    }

    // ---- plan CRUD + offline idempotency ------------------------------------

    @Test
    void add_replayedWithTheSameClientId_doesNotDuplicate() {
        UUID clientId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findById(clientId)).willReturn(Optional.of(stage(clientId, new BigDecimal("500.00"), "Аванс")));

        ProjectPaymentResponse resp = service().add(objectId, ownerId, plan(new BigDecimal("500.00"), "Аванс"), clientId);

        assertThat(resp.id()).isEqualTo(clientId);
        verify(paymentRepository, never()).save(any(ProjectPayment.class));
    }

    @Test
    void add_idOwnedByAnotherObject_isRejected() {
        UUID clientId = UUID.randomUUID();
        Project otherObject = new Project();
        otherObject.setId(UUID.randomUUID());
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findById(clientId)).willReturn(Optional.of(ProjectPayment.builder()
                .id(clientId).project(otherObject).amount(BigDecimal.TEN).purpose("X").sortOrder(0).build()));

        assertThatThrownBy(() -> service().add(objectId, ownerId, plan(BigDecimal.TEN, "X"), clientId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        UUID paymentId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(paymentId, objectId)).willReturn(Optional.empty());

        service().delete(objectId, paymentId, ownerId); // must not throw

        verify(paymentRepository, never()).delete(any(ProjectPayment.class));
    }

    @Test
    void summaryUnchecked_computesRemainingAsContractedMinusReceived_clampedAtZero() {
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("1000.00"));
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of());
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)).willReturn(List.of(
                PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                        .amount(new BigDecimal("1500.00")).receivedAt(LocalDate.now()).label("Своє").build()
        ));

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.contractedTotal()).isEqualByComparingTo("1000.00");
        assertThat(summary.received()).isEqualByComparingTo("1500.00");
        assertThat(summary.remaining()).isEqualByComparingTo("0.00"); // clamped, not negative
        assertThat(summary.unplannedReceipts()).hasSize(1);
    }

    // ---- economy-polish: mutations required PRO — TEMPORARILY open to FREE too, see the
    // comment on Plan.FREE in PlanConfig (opened up while the AI-calling flows are hidden in the
    // PWA to cut AI spend). Reads (list/summary, below) were always open regardless of plan. -----

    @Test
    void add_allowsFreeToo_temporarily() {
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.nextSortOrder(objectId)).willReturn(0);
        given(paymentRepository.save(any(ProjectPayment.class))).willAnswer(inv -> {
            ProjectPayment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            p.setCreatedAt(Instant.now());
            return p;
        });

        ProjectPaymentResponse resp = service().add(objectId, ownerId, plan(BigDecimal.TEN, "Аванс"), null);

        assertThat(resp.amount()).isEqualByComparingTo("10");
    }

    @Test
    void update_allowsFreeToo_temporarily() {
        user(ownerId, Plan.FREE);
        UUID paymentId = UUID.randomUUID();
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(paymentId, objectId))
                .willReturn(Optional.of(stage(paymentId, BigDecimal.TEN, "Аванс")));

        ProjectPaymentResponse resp = service().update(
                objectId, paymentId, ownerId, plan(new BigDecimal("20"), "Аванс 2"));

        assertThat(resp.amount()).isEqualByComparingTo("20");
        assertThat(resp.purpose()).isEqualTo("Аванс 2");
    }

    @Test
    void delete_allowsFreeToo_temporarily() {
        user(ownerId, Plan.FREE);
        UUID paymentId = UUID.randomUUID();
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(paymentId, objectId)).willReturn(Optional.empty());

        service().delete(objectId, paymentId, ownerId); // must not throw
    }

    @Test
    void previewSplit_allowsFreeToo_temporarily() {
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("1000.00"));

        PaymentSplitPreviewResponse resp = service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null));

        assertThat(resp.rows()).hasSize(2);
    }

    @Test
    void commitSplit_allowsFreeToo_temporarily() {
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(new BigDecimal("1000.00"));
        given(paymentRepository.nextSortOrder(objectId)).willReturn(0);
        given(paymentRepository.save(any(ProjectPayment.class))).willAnswer(inv -> {
            ProjectPayment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            p.setCreatedAt(Instant.now());
            return p;
        });

        List<ProjectPaymentResponse> saved = service().commitSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null));

        assertThat(saved).hasSize(2);
    }

    @Test
    void list_and_summary_stayReachableOnFree_onlyMutationsAreGated() {
        // No user() stub at all — if these called requireEconomy, the un-stubbed
        // userRepository.findById would 404 instead of returning data.
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)).willReturn(List.of());

        assertThat(service().list(objectId, ownerId)).isEmpty();
        assertThat(service().summary(objectId, ownerId).contractedTotal()).isEqualByComparingTo("0");
        verify(userRepository, never()).findById(any());
    }

    // ---- FACT: addReceipt — partial/full close ------------------------------

    @Test
    void addReceipt_partial_leavesStagePartialWithCorrectRemaining() {
        UUID stageId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId))
                .willReturn(Optional.of(stage(stageId, new BigDecimal("1000.00"), "Аванс")));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("400.00"), LocalDate.now(), null), null);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("400.00");
        assertThat(saved.get(0).planPaymentId()).isEqualTo(stageId);
    }

    @Test
    void addReceipt_exactRemaining_closesWithNoOverflow() {
        UUID stageId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId))
                .willReturn(Optional.of(stage(stageId, new BigDecimal("500.00"), "Аванс")));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(new BigDecimal("200.00"));
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("300.00"), LocalDate.now(), null), null);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void addReceipt_replayedWithSameClientId_doesNotDuplicate() {
        UUID stageId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findById(clientId)).willReturn(Optional.of(PaymentReceipt.builder()
                .id(clientId).project(object()).planPayment(stage(stageId, new BigDecimal("500"), "Аванс"))
                .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now()).build()));

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("300.00"), LocalDate.now(), null), clientId);

        assertThat(saved).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(clientId));
        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    // ---- FACT: overpayment resolutions ---------------------------------------

    @Test
    void addReceipt_overflow_withNoResolution_throwsValidation() {
        UUID stageId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId))
                .willReturn(Optional.of(stage(stageId, new BigDecimal("500.00"), "Аванс")));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("700.00"), LocalDate.now(), null), null))
                .isInstanceOf(PaymentValidationException.class);

        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    @Test
    void addReceipt_overflow_RESERVE_postsFullAmountAgainstTheSameStage_planUnchanged() {
        UUID stageId = UUID.randomUUID();
        ProjectPayment st = stage(stageId, new BigDecimal("500.00"), "Аванс");
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId)).willReturn(Optional.of(st));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("700.00"), LocalDate.now(),
                        PaymentOverflowResolution.RESERVE), null);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("700.00");
        assertThat(st.getAmount()).isEqualByComparingTo("500.00"); // plan untouched — the reserve
    }

    @Test
    void addReceipt_overflow_INCREASE_bumpsThePlanAmountToTheReceivedTotal() {
        UUID stageId = UUID.randomUUID();
        ProjectPayment st = stage(stageId, new BigDecimal("500.00"), "Аванс");
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId)).willReturn(Optional.of(st));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("700.00"), LocalDate.now(),
                        PaymentOverflowResolution.INCREASE), null);

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("700.00");
        assertThat(st.getAmount()).isEqualByComparingTo("700.00"); // raised to cover it exactly
    }

    @Test
    void addReceipt_overflow_TRANSFER_closesCurrentAndPartiallyFillsTheNextOpenStage() {
        UUID stageId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        ProjectPayment current = stage(stageId, new BigDecimal("500.00"), "Аванс");
        ProjectPayment next = stage(nextId, new BigDecimal("300.00"), "Фінал");
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId)).willReturn(Optional.of(current));
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of(current, next));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.sumByPlanPaymentId(nextId)).willReturn(BigDecimal.ZERO);
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("700.00"), LocalDate.now(),
                        PaymentOverflowResolution.TRANSFER), null);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).planPaymentId()).isEqualTo(stageId);
        assertThat(saved.get(0).amount()).isEqualByComparingTo("500.00"); // closes current exactly
        assertThat(saved.get(1).planPaymentId()).isEqualTo(nextId);
        assertThat(saved.get(1).amount()).isEqualByComparingTo("200.00"); // surplus onto next
        // Σ across the two receipts equals the whole amount received — no money invented or lost.
        BigDecimal sum = saved.stream().map(PaymentReceiptResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("700.00");
    }

    @Test
    void addReceipt_overflow_TRANSFER_noNextOpenStage_throwsValidation() {
        UUID stageId = UUID.randomUUID();
        ProjectPayment current = stage(stageId, new BigDecimal("500.00"), "Аванс");
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(stageId, objectId)).willReturn(Optional.of(current));
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of(current));
        given(receiptRepository.sumByPlanPaymentId(stageId)).willReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(stageId, null, new BigDecimal("700.00"), LocalDate.now(),
                        PaymentOverflowResolution.TRANSFER), null))
                .isInstanceOf(PaymentValidationException.class);
    }

    // ---- FACT: unplanned ("Своє") receipts -----------------------------------

    @Test
    void addReceipt_unplanned_requiresANonBlankLabel() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(null, "  ", new BigDecimal("300.00"), LocalDate.now(), null), null))
                .isInstanceOf(PaymentValidationException.class);
    }

    @Test
    void addReceipt_unplanned_labelCollidingWithAPlanPurpose_isRejected() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId))
                .willReturn(List.of(stage(UUID.randomUUID(), new BigDecimal("500"), "Аванс")));

        assertThatThrownBy(() -> service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(null, "аванс", new BigDecimal("300.00"), LocalDate.now(), null), null))
                .isInstanceOf(PaymentValidationException.class);

        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    @Test
    void addReceipt_unplanned_distinctLabel_isSaved() {
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId))
                .willReturn(List.of(stage(UUID.randomUUID(), new BigDecimal("500"), "Аванс")));
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(null, "Продаж інструменту", new BigDecimal("300.00"), LocalDate.now(), null), null);

        assertThat(saved).singleElement().satisfies(r -> {
            assertThat(r.planPaymentId()).isNull();
            assertThat(r.displayLabel()).isEqualTo("Продаж інструменту");
        });
    }

    // ---- FACT: edit / delete --------------------------------------------------

    @Test
    void editReceipt_updatesAmountAndDate() {
        UUID receiptId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(receiptRepository.findByIdAndProjectId(receiptId, objectId)).willReturn(Optional.of(
                PaymentReceipt.builder().id(receiptId).project(object())
                        .planPayment(stage(UUID.randomUUID(), new BigDecimal("500"), "Аванс"))
                        .amount(new BigDecimal("300.00")).receivedAt(LocalDate.now().minusDays(1)).build()));

        PaymentReceiptResponse resp = service().editReceipt(objectId, receiptId, ownerId,
                new PaymentReceiptEditRequest(new BigDecimal("350.00"), LocalDate.now(), null));

        assertThat(resp.amount()).isEqualByComparingTo("350.00");
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

    @Test
    void addReceipt_allowsFreeToo_temporarily() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: economy
        // opened up to FREE while the AI-calling flows are hidden to cut AI spend.
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of());
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> {
            PaymentReceipt r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        List<PaymentReceiptResponse> saved = service().addReceipt(objectId, ownerId,
                new PaymentReceiptRequest(null, "Щось", BigDecimal.TEN, LocalDate.now(), null), null);

        assertThat(saved).singleElement().satisfies(r -> assertThat(r.displayLabel()).isEqualTo("Щось"));
    }

    // ---- FACT: transferSurplus (the "create a new stage while another is over-received" hint) --

    @Test
    void transferSurplus_reducesTheMostRecentReceiptAndPostsANewOneOnTheTarget() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        ProjectPayment from = stage(fromId, new BigDecimal("500.00"), "Аванс");
        ProjectPayment to = stage(toId, new BigDecimal("4000.00"), "Демонтаж");
        PaymentReceipt onlyReceipt = PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                .planPayment(from).amount(new BigDecimal("700.00")).receivedAt(LocalDate.now()).build();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(fromId, objectId)).willReturn(Optional.of(from));
        given(paymentRepository.findByIdAndProjectId(toId, objectId)).willReturn(Optional.of(to));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(fromId))
                .willReturn(new ArrayList<>(List.of(onlyReceipt)));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(toId)).willReturn(List.of());
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> inv.getArgument(0));

        service().transferSurplus(objectId, ownerId, new PaymentSurplusTransferRequest(fromId, toId));

        assertThat(onlyReceipt.getAmount()).isEqualByComparingTo("500.00"); // 700 - 200 surplus
        ArgumentCaptor<PaymentReceipt> saved = ArgumentCaptor.forClass(PaymentReceipt.class);
        verify(receiptRepository).save(saved.capture());
        assertThat(saved.getValue().getPlanPayment()).isEqualTo(to);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("200.00");
        verify(receiptRepository, never()).delete(any(PaymentReceipt.class));
    }

    @Test
    void transferSurplus_cascadesToAnOlderReceiptWhenTheNewestCantCoverTheWholeSurplus() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        ProjectPayment from = stage(fromId, new BigDecimal("3000.00"), "Аванс");
        ProjectPayment to = stage(toId, new BigDecimal("4000.00"), "Демонтаж");
        PaymentReceipt older = PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                .planPayment(from).amount(new BigDecimal("3500.00")).receivedAt(LocalDate.now().minusDays(1)).build();
        PaymentReceipt newer = PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                .planPayment(from).amount(new BigDecimal("500.00")).receivedAt(LocalDate.now()).build();
        // Σ = 4000, plan = 3000 → surplus = 1000; newest (500) fully consumed, older reduced by 500.
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(fromId, objectId)).willReturn(Optional.of(from));
        given(paymentRepository.findByIdAndProjectId(toId, objectId)).willReturn(Optional.of(to));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(fromId))
                .willReturn(new ArrayList<>(List.of(older, newer)));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(toId)).willReturn(List.of());
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> inv.getArgument(0));

        service().transferSurplus(objectId, ownerId, new PaymentSurplusTransferRequest(fromId, toId));

        verify(receiptRepository).delete(newer);
        assertThat(older.getAmount()).isEqualByComparingTo("3000.00"); // 3500 - 500
        ArgumentCaptor<PaymentReceipt> saved = ArgumentCaptor.forClass(PaymentReceipt.class);
        verify(receiptRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void transferSurplus_noSurplus_throwsValidation() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        ProjectPayment from = stage(fromId, new BigDecimal("500.00"), "Аванс");
        ProjectPayment to = stage(toId, new BigDecimal("4000.00"), "Демонтаж");
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(fromId, objectId)).willReturn(Optional.of(from));
        given(paymentRepository.findByIdAndProjectId(toId, objectId)).willReturn(Optional.of(to));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(fromId)).willReturn(List.of());

        assertThatThrownBy(() -> service().transferSurplus(objectId, ownerId,
                new PaymentSurplusTransferRequest(fromId, toId)))
                .isInstanceOf(PaymentValidationException.class);

        verify(receiptRepository, never()).save(any(PaymentReceipt.class));
    }

    @Test
    void transferSurplus_allowsFreeToo_temporarily() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: economy
        // opened up to FREE while the AI-calling flows are hidden to cut AI spend.
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        ProjectPayment from = stage(fromId, new BigDecimal("500.00"), "Аванс");
        ProjectPayment to = stage(toId, new BigDecimal("4000.00"), "Демонтаж");
        PaymentReceipt onlyReceipt = PaymentReceipt.builder().id(UUID.randomUUID()).project(object())
                .planPayment(from).amount(new BigDecimal("700.00")).receivedAt(LocalDate.now()).build();
        user(ownerId, Plan.FREE);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(fromId, objectId)).willReturn(Optional.of(from));
        given(paymentRepository.findByIdAndProjectId(toId, objectId)).willReturn(Optional.of(to));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(fromId))
                .willReturn(new ArrayList<>(List.of(onlyReceipt)));
        given(receiptRepository.findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(toId)).willReturn(List.of());
        given(receiptRepository.save(any(PaymentReceipt.class))).willAnswer(inv -> inv.getArgument(0));

        service().transferSurplus(objectId, ownerId, new PaymentSurplusTransferRequest(fromId, toId));

        assertThat(onlyReceipt.getAmount()).isEqualByComparingTo("500.00"); // 700 - 200 surplus
    }
}
