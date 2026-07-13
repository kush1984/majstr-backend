package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

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

    // The REAL gate (backed by PlanConfig) so the PRO/FREE decision is genuinely tested.
    private final DefaultFeatureGuard featureGuard = new DefaultFeatureGuard();

    private ObjectExpenseService service() {
        return new ObjectExpenseService(expenseRepository, estimateRepository, projectService,
                userRepository, featureGuard);
    }

    private void user(UUID id, Plan plan) {
        given(userRepository.findById(id)).willReturn(Optional.of(User.builder().id(id).plan(plan).build()));
    }

    private Project object(ProjectStatus status) {
        Project p = new Project();
        p.setStatus(status);
        return p;
    }

    @Test
    void freeUser_isBlockedBeforeAnyObjectRead() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.FREE);

        assertThatThrownBy(() -> service().economy(object, owner))
                .isInstanceOf(FeatureNotAvailableException.class);

        // Gate fires first — ownership/data never touched.
        verify(projectService, never()).loadOwned(any(), any());
        verify(estimateRepository, never()).sumWorksCounted(any());
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
    void economy_worksAreEarnings_manualReducesThem_materialsAreCash() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        given(estimateRepository.sumWorksCounted(object)).willReturn(new BigDecimal("10000.00"));
        given(estimateRepository.sumMaterialsCounted(object)).willReturn(new BigDecimal("4000.00"));
        given(estimateRepository.sumDepositsCounted(object)).willReturn(new BigDecimal("6000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.RECEIPT)).willReturn(new BigDecimal("3000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.MANUAL)).willReturn(new BigDecimal("500.00"));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.works()).isEqualByComparingTo("10000.00");
        assertThat(eco.materials()).isEqualByComparingTo("4000.00");     // reference, not earnings
        assertThat(eco.received()).isEqualByComparingTo("6000.00");
        assertThat(eco.spentReceipts()).isEqualByComparingTo("3000.00");
        assertThat(eco.spentManual()).isEqualByComparingTo("500.00");
        // Not completed → profit = works − manual (materials NOT included).
        assertThat(eco.profit()).isEqualByComparingTo("9500.00");        // 10000 − 500
        assertThat(eco.cashBalance()).isEqualByComparingTo("3000.00");   // received 6000 − receipts 3000
    }

    @Test
    void economy_cashGoesNegativeWhenReceiptsExceedDeposit_store_run() {
        // Client paid a 3000 deposit, the master spent 5000 on receipts out of pocket
        // → materials cash −2000 (NOT clamped). Profit is works − manual, unaffected.
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.IN_PROGRESS));
        given(estimateRepository.sumWorksCounted(object)).willReturn(new BigDecimal("15000.00"));
        given(estimateRepository.sumMaterialsCounted(object)).willReturn(new BigDecimal("6000.00"));
        given(estimateRepository.sumDepositsCounted(object)).willReturn(new BigDecimal("3000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.RECEIPT)).willReturn(new BigDecimal("5000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.MANUAL)).willReturn(new BigDecimal("0.00"));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.cashBalance()).isEqualByComparingTo("-2000.00");  // 3000 − 5000
        assertThat(eco.profit()).isEqualByComparingTo("15000.00");       // works − manual (not completed)
    }

    @Test
    void economy_completedObject_leftoverDepositSettlesIntoProfit() {
        // Object CLOSED: the materials pot (received − receipts = 1000 leftover) becomes earnings.
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(object(ProjectStatus.COMPLETED));
        given(estimateRepository.sumWorksCounted(object)).willReturn(new BigDecimal("10000.00"));
        given(estimateRepository.sumMaterialsCounted(object)).willReturn(new BigDecimal("4000.00"));
        given(estimateRepository.sumDepositsCounted(object)).willReturn(new BigDecimal("4000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.RECEIPT)).willReturn(new BigDecimal("3000.00"));
        given(expenseRepository.sumBySource(object, ExpenseSource.MANUAL)).willReturn(new BigDecimal("200.00"));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.cashBalance()).isEqualByComparingTo("1000.00");   // 4000 − 3000 leftover
        // Completed → profit = works − manual + leftover = 10000 − 200 + 1000.
        assertThat(eco.profit()).isEqualByComparingTo("10800.00");
    }
}
