package com.majstr.backend.integration;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
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
 */
class ObjectEconomyQueriesIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;

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
        assertThat(estimateRepository.sumWorksCounted(projectId))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void unflaggedEstimatesAreExcludedToo_soVariantsDoNotDoubleCount() {
        // The original reason the flag exists: econom/premium variants and a consolidated
        // rollup must not all be summed into one object's income.
        estimateWith(EstimateStatus.SENT, true, ItemType.WORK, "800.00");
        estimateWith(EstimateStatus.DRAFT, false, ItemType.WORK, "9999.00");

        assertThat(estimateRepository.sumIncomeCounted(projectId))
                .isEqualByComparingTo("800.00");
    }

    @Test
    void worksAndMaterialsSplitTheCountedTotalBetweenThem() {
        // The master's earnings base is WORK only; materials are a passthrough. If the
        // REJECTED guard were added to one query and forgotten in another, the two would
        // stop adding up to the income figure — so assert the relationship, not just values.
        Estimate signed = estimateWith(EstimateStatus.SIGNED, true, ItemType.WORK, "600.00");
        addItem(signed, ItemType.MATERIAL, "400.00");
        estimateWith(EstimateStatus.REJECTED, true, ItemType.MATERIAL, "7777.00");

        BigDecimal income = estimateRepository.sumIncomeCounted(projectId);
        BigDecimal works = estimateRepository.sumWorksCounted(projectId);
        BigDecimal materials = estimateRepository.sumMaterialsCounted(projectId);

        assertThat(works).isEqualByComparingTo("600.00");
        assertThat(materials).isEqualByComparingTo("400.00"); // not 8177 — the rejected one is out
        assertThat(works.add(materials)).isEqualByComparingTo(income);
    }

    @Test
    void anObjectWithNoEstimatesSumsToZero_notNull() {
        // COALESCE is load-bearing: a null here would NPE on unboxing in the economy service.
        assertThat(estimateRepository.sumIncomeCounted(projectId)).isEqualByComparingTo("0");
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
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate)
                .type(type)
                .name("Позиція")
                .unit(Unit.M2)
                .quantity(new BigDecimal("1.000"))
                .unitPrice(new BigDecimal(amount))
                .sortOrder(0)
                .build());
    }
}
