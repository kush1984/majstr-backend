package com.majstr.backend.service;

import com.majstr.backend.dto.ExpenseRequest;
import com.majstr.backend.dto.ExpenseResponse;
import com.majstr.backend.dto.ObjectEconomyResponse;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.DefaultFeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ObjectExpenseRepository.CategoryTotal;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    private static CategoryTotal cat(ExpenseCategory c, String total) {
        return new CategoryTotal() {
            public ExpenseCategory getCategory() { return c; }
            public BigDecimal getTotal() { return new BigDecimal(total); }
        };
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
        verify(estimateRepository, never()).sumIncomeExcludingRejected(any());
    }

    @Test
    void proUser_add_persistsWithDefaults() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(new Project());
        given(expenseRepository.save(any(ObjectExpense.class))).willAnswer(i -> i.getArgument(0));

        ExpenseResponse res = service().add(object, owner,
                new ExpenseRequest(new BigDecimal("450.00"), ExpenseCategory.MATERIALS, "  клей  ", null));

        assertThat(res.amount()).isEqualByComparingTo("450.00");
        assertThat(res.category()).isEqualTo(ExpenseCategory.MATERIALS);
        assertThat(res.note()).isEqualTo("клей");              // trimmed
        assertThat(res.spentAt()).isEqualTo(LocalDate.now());  // defaulted to today
    }

    @Test
    void economy_incomeExcludesRejected_andProfitIsIncomeMinusExpenses() {
        UUID owner = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        user(owner, Plan.PRO);
        given(projectService.loadOwned(object, owner)).willReturn(new Project());
        given(estimateRepository.sumIncomeExcludingRejected(object)).willReturn(new BigDecimal("10000.00"));
        given(estimateRepository.sumIncomeSigned(object)).willReturn(new BigDecimal("6000.00"));
        given(expenseRepository.sumByCategory(object)).willReturn(List.of(
                cat(ExpenseCategory.MATERIALS, "3000.00"),
                cat(ExpenseCategory.LABOR, "1000.00")));

        ObjectEconomyResponse eco = service().economy(object, owner);

        assertThat(eco.incomeTotal()).isEqualByComparingTo("10000.00");
        assertThat(eco.incomeSigned()).isEqualByComparingTo("6000.00");
        assertThat(eco.expensesTotal()).isEqualByComparingTo("4000.00");
        assertThat(eco.profit()).isEqualByComparingTo("6000.00");        // 10000 − 4000
        assertThat(eco.profitSigned()).isEqualByComparingTo("2000.00");  // 6000 − 4000
        assertThat(eco.expensesByCategory()).containsEntry(ExpenseCategory.MATERIALS, new BigDecimal("3000.00"));
    }
}
