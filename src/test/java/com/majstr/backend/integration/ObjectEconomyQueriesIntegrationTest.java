package com.majstr.backend.integration;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ExpenseCategory;
import com.majstr.backend.entity.ExpenseSource;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.ObjectExpense;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ObjectExpenseRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object-economy money queries, run as real SQL against real Postgres.
 *
 * <p>These are {@code nativeQuery = true} sums, so no Mockito test can reach them — the
 * mock returns whatever it is told. That is exactly how the audit's M6 survived: V57
 * blanket-set {@code count_in_economy = TRUE} on every estimate, {@code sumIncomeCounted}
 * filtered on the flag alone, and every master with a rejected estimate silently saw
 * inflated income. The {@code AND e.status <> 'REJECTED'} guard added in batch C was, until
 * this file, the one fix in the whole audit sweep with no test at all.</p>
 *
 * <p><b>Economy-rework iteration:</b> {@code sumWorksCounted}/{@code sumMaterialsCounted} (the
 * duplicate-margin CASE formula) are gone — profit is now {@code sumIncomeCounted −
 * ObjectExpenseRepository.sumAll}, so the tests that used to exercise the deleted formula were
 * removed rather than adapted; {@code sumAllExpensesAcrossCategoriesAndSources} below covers the
 * query that replaced them.</p>
 *
 * <p><b>economy-contracted-signed-only-fix:</b> {@code count_in_economy} defaults {@code true}
 * even on a fresh DRAFT — it means "counts once signed," not "is signed" — so {@code
 * sumIncomeCounted} missing an {@code AND status = 'SIGNED'} let a flagged DRAFT/SENT estimate's
 * amount leak into "За договором" while the act panels (already {@code SIGNED}-only) stayed
 * correctly empty. {@code contractedIgnoresUnsignedEstimatesEvenWhenFlagged} below is the
 * regression test for exactly that prod bug.</p>
 */
class ObjectEconomyQueriesIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;
    @Autowired ObjectExpenseRepository expenseRepository;

    private UUID projectId;

    @BeforeEach
    void setUp() {
        // The container is shared across the whole run, so build a fresh owner+object per
        // test and scope every assertion to it rather than assuming an empty database.
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.PRO)
                .referralCode(unique.substring(0, 10)) // NOT NULL since V41, no entity default
                .build());

        Project project = projectRepository.save(Project.builder()
                .owner(owner)
                .name("Обʼєкт")
                .address("вул. Тестова, 1")
                .status(ProjectStatus.DRAFT)
                .build());
        projectId = project.getId();
    }

    @Test
    void rejectedEstimatesAreNotIncome_evenWhileTheFlagSaysTheyAre() {
        // Both are FLAGGED to count — this is precisely the state V57 left every existing
        // estimate in, so the flag alone cannot be the filter.
        estimateWith(EstimateStatus.SIGNED, true, ItemType.WORK, "1000.00");
        estimateWith(EstimateStatus.REJECTED, true, ItemType.WORK, "500.00");

        assertThat(estimateRepository.sumIncomeCounted(projectId))
                .isEqualByComparingTo("1000.00"); // the rejected 500 must not be in here
    }

    @Test
    void unflaggedEstimatesAreExcludedToo_soVariantsDoNotDoubleCount() {
        // The original reason the flag exists: econom/premium variants and a consolidated
        // rollup must not all be summed into one object's income. Both SIGNED here (flag alone
        // is no longer sufficient after the SIGNED-only fix — status matters too), so this isolates
        // what the flag itself is still responsible for excluding.
        estimateWith(EstimateStatus.SIGNED, true, ItemType.WORK, "800.00");
        estimateWith(EstimateStatus.SIGNED, false, ItemType.WORK, "9999.00");

        assertThat(estimateRepository.sumIncomeCounted(projectId))
                .isEqualByComparingTo("800.00");
    }

    @Test
    void contractedIgnoresUnsignedEstimatesEvenWhenFlagged() {
        // The exact prod bug: an object with only DRAFT/SENT estimates (both flagged — the
        // default) must show contracted = 0, matching the empty act-panels list, not the sum of
        // draft/sent amounts. Signing one then makes contracted equal to ONLY that one's amount.
        estimateWith(EstimateStatus.DRAFT, true, ItemType.WORK, "40.00");
        estimateWith(EstimateStatus.SENT, true, ItemType.WORK, "19725.00");

        assertThat(estimateRepository.sumIncomeCounted(projectId)).isEqualByComparingTo("0");

        estimateWith(EstimateStatus.SIGNED, true, ItemType.WORK, "1200.00");

        assertThat(estimateRepository.sumIncomeCounted(projectId)).isEqualByComparingTo("1200.00");
    }

    @Test
    void anObjectWithNoEstimatesSumsToZero_notNull() {
        // COALESCE is load-bearing: a null here would NPE on unboxing in the economy service.
        assertThat(estimateRepository.sumIncomeCounted(projectId)).isEqualByComparingTo("0");
    }

    @Test
    void sumAllExpensesAcrossCategoriesAndSources() {
        // The single "Витрати" figure the simplified profit model subtracts from the contracted
        // total — must fold in every category (materials/labor/other) AND every source
        // (receipt-imported or hand-entered), unlike the old spentReceipts/spentManual split.
        expenseRepository.save(ObjectExpense.builder()
                .objectId(projectId).amount(new BigDecimal("3000.00"))
                .category(ExpenseCategory.MATERIALS).source(ExpenseSource.RECEIPT)
                .spentAt(LocalDate.now()).build());
        expenseRepository.save(ObjectExpense.builder()
                .objectId(projectId).amount(new BigDecimal("1500.00"))
                .category(ExpenseCategory.LABOR).source(ExpenseSource.MANUAL)
                .spentAt(LocalDate.now()).build());
        expenseRepository.save(ObjectExpense.builder()
                .objectId(projectId).amount(new BigDecimal("200.00"))
                .category(ExpenseCategory.OTHER).source(ExpenseSource.MANUAL)
                .spentAt(LocalDate.now()).build());

        assertThat(expenseRepository.sumAll(projectId)).isEqualByComparingTo("4700.00");
    }

    @Test
    void findSignedEstimateSummaries_worksAndMaterialsAreGross_notNetOfTheDiscount() {
        // The live bug: a 15% TOTAL-kind discount applied against WORK lines used to fold its own
        // line_total into the raw `works` sum, so the panel showed "Роботи: 22 100" — the ALREADY
        // discounted figure, identical to "Разом" — with nothing left for the Знижка recap to
        // explain. `works` must come back gross (26 000, matching what the estimate's own view
        // shows), with the discount reported separately and total reconciling as
        // works + materials + markup + discount.
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .status(EstimateStatus.SIGNED)
                .countInEconomy(true)
                .build());
        addItem(estimate, ItemType.WORK, "10000.00");
        addItem(estimate, ItemType.WORK, "16000.00");
        addPercentItem(estimate, ItemType.WORK, PercentBaseKind.TOTAL, "-3900.00"); // 15% of 26 000

        Object[] row = estimateRepository.findSignedEstimateSummaries(projectId).get(0);
        BigDecimal works = (BigDecimal) row[4];
        BigDecimal materials = (BigDecimal) row[5];
        BigDecimal markup = (BigDecimal) row[6];
        BigDecimal discount = (BigDecimal) row[7];

        assertThat(works).isEqualByComparingTo("26000.00"); // gross — the discount line excluded
        assertThat(materials).isEqualByComparingTo("0");
        assertThat(markup).isEqualByComparingTo("0");
        assertThat(discount).isEqualByComparingTo("-3900.00");
        // The caller (ObjectExpenseService.signedEstimatePanels) reconstitutes the real signed
        // total from these four — verify that reconciliation lands on the actual contracted amount.
        assertThat(works.add(materials).add(markup).add(discount)).isEqualByComparingTo("22100.00");
    }

    // ---- helpers ----------------------------------------------------------------

    private Estimate estimateWith(EstimateStatus status, boolean counted, ItemType type, String amount) {
        Estimate estimate = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .status(status)
                .countInEconomy(counted)
                .build());
        addItem(estimate, type, amount);
        return estimate;
    }

    /** One line whose quantity is 1, so the line total equals the amount exactly. */
    private void addItem(Estimate estimate, ItemType type, String amount) {
        // lineTotal is set explicitly because these fixtures bypass the service, which is the only
        // thing that writes it in production (V88). Leaving it at the column default of 0 would
        // make every sum here read zero — and the test would be measuring the fixture, not the SQL.
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate)
                .type(type)
                .name("Позиція")
                .unit(Unit.M2)
                .quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal(amount))
                .lineTotal(new BigDecimal(amount).setScale(2))
                .sortOrder(0)
                .build());
    }

    /** A PERCENT-unit line («% від кошторису» / «% від типу») — the kind excluded from the raw
     *  works/materials sum and reported via markup/discount instead. */
    private void addPercentItem(Estimate estimate, ItemType type, PercentBaseKind baseKind, String lineTotal) {
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate)
                .type(type)
                .name("Знижка")
                .unit(Unit.PERCENT)
                .percentBaseKind(baseKind)
                .quantity(new BigDecimal("15.000"))
                .unitPrice(BigDecimal.ZERO)
                .lineTotal(new BigDecimal(lineTotal).setScale(2))
                .sortOrder(1)
                .build());
    }
}
