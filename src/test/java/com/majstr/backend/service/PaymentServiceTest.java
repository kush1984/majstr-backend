package com.majstr.backend.service;

import com.majstr.backend.dto.PaymentSplitPreviewResponse;
import com.majstr.backend.dto.PaymentSplitRequest;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.PaymentSplitPreset;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.ProjectPaymentStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentSplitException;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPaymentRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
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
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectService projectService;
    @Mock UserRepository userRepository;

    // The REAL gate (backed by PlanConfig), same pattern ObjectExpenseServiceTest uses — the
    // PRO/FREE decision is genuinely exercised, not mocked away.
    private final DefaultFeatureGuard featureGuard = new DefaultFeatureGuard();

    private PaymentService service() {
        return new PaymentService(paymentRepository, estimateRepository, projectService, userRepository, featureGuard);
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

    // ---- CRUD + offline idempotency -----------------------------------------

    @Test
    void add_replayedWithTheSameClientId_doesNotDuplicate() {
        UUID clientId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findById(clientId)).willReturn(Optional.of(ProjectPayment.builder()
                .id(clientId).project(object()).amount(new BigDecimal("500.00")).purpose("Аванс")
                .sortOrder(0).build()));

        ProjectPaymentResponse resp = service().add(objectId, ownerId,
                new ProjectPaymentRequest(new BigDecimal("500.00"), null, null, "Аванс", null, null),
                clientId);

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

        assertThatThrownBy(() -> service().add(objectId, ownerId,
                new ProjectPaymentRequest(BigDecimal.TEN, null, null, "X", null, null), clientId))
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
        given(paymentRepository.sumPaidByProjectId(objectId)).willReturn(new BigDecimal("1500.00")); // overpaid
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of());

        PaymentsSummaryResponse summary = service().summaryUnchecked(objectId);

        assertThat(summary.contractedTotal()).isEqualByComparingTo("1000.00");
        assertThat(summary.received()).isEqualByComparingTo("1500.00");
        assertThat(summary.remaining()).isEqualByComparingTo("0.00"); // clamped, not negative
    }

    @Test
    void update_marksReceived_wholeRowReplaced() {
        user(ownerId, Plan.PRO);
        UUID paymentId = UUID.randomUUID();
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByIdAndProjectId(paymentId, objectId)).willReturn(Optional.of(
                ProjectPayment.builder().id(paymentId).project(object()).amount(new BigDecimal("500.00"))
                        .purpose("Аванс").sortOrder(0).build()));

        ProjectPaymentResponse resp = service().update(objectId, paymentId, ownerId,
                new ProjectPaymentRequest(new BigDecimal("500.00"), null, null, "Аванс",
                        new BigDecimal("500.00"), Instant.now()));

        assertThat(resp.paidAmount()).isEqualByComparingTo("500.00");
        assertThat(resp.status()).isEqualTo(ProjectPaymentStatus.RECEIVED);
    }

    // ---- economy-polish: mutations require PRO, reads stay open ------------

    @Test
    void add_rejectsFreeBeforeAnyObjectRead() {
        user(ownerId, Plan.FREE);

        assertThatThrownBy(() -> service().add(objectId, ownerId,
                new ProjectPaymentRequest(BigDecimal.TEN, null, null, "Аванс", null, null), null))
                .isInstanceOf(FeatureNotAvailableException.class);

        verify(projectService, never()).loadOwned(any(), any());
    }

    @Test
    void update_rejectsFree() {
        user(ownerId, Plan.FREE);

        assertThatThrownBy(() -> service().update(objectId, UUID.randomUUID(), ownerId,
                new ProjectPaymentRequest(BigDecimal.TEN, null, null, "Аванс", null, null)))
                .isInstanceOf(FeatureNotAvailableException.class);
    }

    @Test
    void delete_rejectsFree() {
        user(ownerId, Plan.FREE);

        assertThatThrownBy(() -> service().delete(objectId, UUID.randomUUID(), ownerId))
                .isInstanceOf(FeatureNotAvailableException.class);

        verify(paymentRepository, never()).delete(any(ProjectPayment.class));
    }

    @Test
    void previewSplit_rejectsFree() {
        user(ownerId, Plan.FREE);

        assertThatThrownBy(() -> service().previewSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null)))
                .isInstanceOf(FeatureNotAvailableException.class);
    }

    @Test
    void commitSplit_rejectsFree() {
        user(ownerId, Plan.FREE);

        assertThatThrownBy(() -> service().commitSplit(objectId, ownerId,
                new PaymentSplitRequest(PaymentSplitPreset.FIFTY_FIFTY, null)))
                .isInstanceOf(FeatureNotAvailableException.class);

        verify(paymentRepository, never()).save(any(ProjectPayment.class));
    }

    @Test
    void list_and_summary_stayReachableOnFree_onlyMutationsAreGated() {
        // No user() stub at all — if these called requireEconomy, the un-stubbed
        // userRepository.findById would 404 instead of returning data.
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object());
        given(paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)).willReturn(List.of());
        given(estimateRepository.sumIncomeCounted(objectId)).willReturn(BigDecimal.ZERO);
        given(paymentRepository.sumPaidByProjectId(objectId)).willReturn(BigDecimal.ZERO);

        assertThat(service().list(objectId, ownerId)).isEmpty();
        assertThat(service().summary(objectId, ownerId).contractedTotal()).isEqualByComparingTo("0");
        verify(userRepository, never()).findById(any());
    }
}
