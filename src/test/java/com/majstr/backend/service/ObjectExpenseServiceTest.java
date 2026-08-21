package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ObjectExpenseServiceTest {

    @Mock ObjectExpenseRepository expenseRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectService projectService;
    @Mock UserRepository userRepository;
    @Mock PaymentService paymentService;
    @Mock WorkActItemRepository workActItemRepository;
    @Mock WorkActReceiptRepository workActReceiptRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;

    // The REAL gate (backed by PlanConfig) so the PRO/FREE decision is genuinely tested.
    private final DefaultFeatureGuard featureGuard = new DefaultFeatureGuard();

    private ObjectExpenseService service() {
        return new ObjectExpenseService(expenseRepository, estimateRepository, projectService,
                userRepository, featureGuard, paymentService, workActItemRepository, workActReceiptRepository,
                paymentReceiptRepository);
    }

    /** The works axis sums two queries (act lines + act receipts) and adds them — both are
     *  COALESCE'd in SQL, so a mock must return a number, never null. */
    private void actsAxisZero(UUID object) {
        given(workActItemRepository.sumSignedActLineTotals(object)).willReturn(BigDecimal.ZERO);
        given(workActReceiptRepository.sumSignedActReceipts(object)).willReturn(BigDecimal.ZERO);
    }

    private void user(UUID id, Plan plan) {
        given(userRepository.findById(id)).willReturn(Optional.of(User.builder().id(id).plan(plan).build()));
    }

    private Project object(ProjectStatus status) {
        Project p = new Project();
        p.setStatus(status);
        return p;
    }

    private static PaymentsSummaryResponse payments(BigDecimal received) {
        return new PaymentsSummaryResponse(BigDecimal.ZERO, received, BigDecimal.ZERO, List.of(), List.of());
    }

    private static PaymentsSummaryResponse payments(BigDecimal contractedTotal, BigDecimal received) {
        return new PaymentsSummaryResponse(contractedTotal, received, BigDecimal.ZERO, List.of(), List.of());
    }

    // ---- FREE/PRO split on economy() ---------------------------------------

    @Test
    void freeUser_economy_getsBothPaymentsAndInternalsToo_temporarily() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: economy
        // opened up to FREE (previously: FREE saw only the signed-acts panels, nothing past
        // them) while the AI-calling flows are hidden in the PWA to cut AI spend.
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.FREE);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        actsAxisZero(object);
        given(paymentService.summaryUnchecked(object)).willReturn(payments(BigDecimal.ZERO));
        given(expenseRepository.sumAll(object)).willReturn(BigDecimal.ZERO);

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.payments()).isNotNull();
        assertThat(eco.internals()).isNotNull();
    }

    @Test
    void proUser_economy_getsBothPaymentsAndInternals() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        actsAxisZero(object);
        given(paymentService.summaryUnchecked(object)).willReturn(payments(BigDecimal.ZERO));
        given(expenseRepository.sumAll(object)).willReturn(BigDecimal.ZERO);

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.payments()).isNotNull();
        assertThat(eco.internals()).isNotNull();
    }

    @Test
    void expenseJournal_alsoAllowsFreeNow_temporarily() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: the expense
        // journal (add/list/update/delete) shares requireEconomy with economy(), so it opened up
        // to FREE at the same time, for the same reason.
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.FREE);
        given(projectService.loadOwned(object, owner)).willReturn(new Project());

        assertThatCode(() -> service().list(object, owner)).doesNotThrowAnyException();
    }

    @Test
    void proUser_add_persistsWithDefaults() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(new Project());
        given(expenseRepository.save(any(ObjectExpense.class))).willAnswer(i -> i.getArgument(0));

        ExpenseResponse res = service().add(object, owner,
                new ExpenseRequest(new BigDecimal("450.00"), ExpenseCategory.MATERIALS, "  клей  ", null, null));

        assertThat(res.amount()).isEqualByComparingTo("450.00");
        assertThat(res.category()).isEqualTo(ExpenseCategory.MATERIALS);
        assertThat(res.note()).isEqualTo("клей");              // trimmed
        assertThat(res.spentAt()).isEqualTo(LocalDate.now());  // defaulted to today
        assertThat(res.source()).isEqualTo(ExpenseSource.MANUAL); // hand-entered → unforeseen
    }

    @Test
    void economy_profitIsContractedMinusEveryExpense_expensesIsTheirSum() {
        // Economy-rework: no works/materials/cash split — profit reads straight off the same
        // contracted total the payments block already shows, minus every object_expense
        // regardless of category or source (materials, crew wages logged as LABOR, anything else).
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        actsAxisZero(object);
        given(paymentService.summaryUnchecked(object))
                .willReturn(payments(new BigDecimal("14000.00"), new BigDecimal("6000.00")));
        given(expenseRepository.sumAll(object)).willReturn(new BigDecimal("3500.00"));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.internals().expenses()).isEqualByComparingTo("3500.00");
        assertThat(eco.internals().profit()).isEqualByComparingTo("10500.00"); // 14000 − 3500
    }

    @Test
    void economy_expensesExceedingContracted_goesNegativeNotClamped() {
        // The master spent more than the contracted total (materials out of pocket, or an
        // over-budget crew payment) — profit is honestly negative, not floored at zero.
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        actsAxisZero(object);
        given(paymentService.summaryUnchecked(object))
                .willReturn(payments(new BigDecimal("3000.00"), new BigDecimal("3000.00")));
        given(expenseRepository.sumAll(object)).willReturn(new BigDecimal("5000.00"));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.internals().profit()).isEqualByComparingTo("-2000.00"); // 3000 − 5000
    }

    // ---- offline authoring (client-supplied ids) ---------------------------

    @Test
    void add_replayedWithTheSameClientId_doesNotDoubleCountTheMoney() {
        // Money must never duplicate: a second copy of an expense silently understates the
        // object's profit, and the master would be reading a wrong number about their own cash.
        UUID ownerId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object(ProjectStatus.IN_PROGRESS));
        given(expenseRepository.findById(clientId)).willReturn(Optional.of(ObjectExpense.builder()
                .id(clientId).objectId(objectId).amount(new BigDecimal("450.00"))
                .category(ExpenseCategory.MATERIALS).source(ExpenseSource.MANUAL).build()));

        var resp = service().add(objectId, ownerId,
                new ExpenseRequest(new BigDecimal("450.00"), ExpenseCategory.MATERIALS, null, null, null),
                clientId);

        assertThat(resp.id()).isEqualTo(clientId);
        verify(expenseRepository, never()).save(any(ObjectExpense.class));
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        UUID ownerId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        UUID expenseId = UUID.randomUUID();
        user(ownerId, Plan.PRO);
        given(projectService.loadOwned(objectId, ownerId)).willReturn(object(ProjectStatus.IN_PROGRESS));
        given(expenseRepository.findByIdAndObjectId(expenseId, objectId)).willReturn(Optional.empty());

        assertThatCode(() -> service().delete(objectId, expenseId, ownerId)).doesNotThrowAnyException();

        verify(expenseRepository, never()).delete(any(ObjectExpense.class));
    }
}
