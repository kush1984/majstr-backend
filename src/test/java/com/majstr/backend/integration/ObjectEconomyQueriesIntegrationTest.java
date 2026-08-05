package com.majstr.backend.integration;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
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

    // ---- a percentage line inside a marked-up duplicate --------------------------
    //
    // The whole point of the duplicate mechanic is that the foreman's earnings are the DIFFERENCE
    // between the two sheets. A percentage line has no price to subtract, so without reaching its
    // base the economy reports zero for it — and the master reads a smaller income than he has,
    // with nothing on screen to explain why. Both shapes below are real and both must show margin.

    @Test
    void percentOfAMATERIAL_carriesARaisedPercent_andItsMarginIsTheDifference() {
        // Шафа 5 000 ₴ (material — never marked up), монтаж «20 % від шафи», markup +30 %.
        // Materials do not move, so the PERCENT does: 20 % → 26 %, i.e. 1 300 against 1 000.
        Estimate parent = estimateWith(EstimateStatus.DRAFT, false, ItemType.MATERIAL, "5000");
        EstimateItem wardrobe = itemRepository
                .findByEstimateIdOrderBySortOrderAscIdAsc(parent.getId()).get(0);

        Estimate copy = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .status(EstimateStatus.DRAFT)
                .countInEconomy(true)
                .duplicatedFromId(parent.getId())
                .build());
        EstimateItem wardrobeCopy = itemRepository.save(EstimateItem.builder()
                .estimate(copy).type(ItemType.MATERIAL).name("Шафа").unit(Unit.PIECE)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal("5000.00"))
                .sourceUnitPrice(new BigDecimal("5000.00")).sourceItemId(wardrobe.getId())
                .lineTotal(new BigDecimal("5000.00")).sortOrder(0).build());
        itemRepository.save(EstimateItem.builder()
                .estimate(copy).type(ItemType.WORK).name("Монтаж").unit(Unit.PERCENT)
                .quantity(new BigDecimal("26.000"))            // 20 % × 1,3
                .unitPrice(BigDecimal.ZERO)
                .sourceUnitPrice(new BigDecimal("20.00"))      // the crew's own percent
                .percentBaseKind(PercentBaseKind.POSITION)
                .percentBaseItemId(wardrobeCopy.getId())
                .lineTotal(new BigDecimal("1300.00"))
                .sortOrder(1).build());

        // 1 300 charged − 1 000 the crew is paid = 300.
        assertThat(estimateRepository.sumWorksCounted(projectId)).isEqualByComparingTo("300.00");
    }

    @Test
    void percentOfAWORK_keepsItsPercent_andStillEarnsBecauseTheBaseGrew() {
        // Робота 1 000 ₴ marked up +30 % → 1 300. «20 % від роботи» keeps 20 % — the base already
        // grew, and raising the percent too would mark the line up twice. 20 % of 1 300 is 260
        // against the parent's 200, so 60 is real margin that must not read as zero.
        // A REAL parent: duplicated_from_id carries a foreign key, so a random uuid is rejected by
        // the database rather than quietly standing in for "some other estimate".
        Estimate parent = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .status(EstimateStatus.DRAFT)
                .countInEconomy(false)   // the crew's sheet leaves the economy on duplication
                .build());
        Estimate copy = estimateRepository.save(Estimate.builder()
                .project(projectRepository.findById(projectId).orElseThrow())
                .status(EstimateStatus.DRAFT)
                .countInEconomy(true)
                .duplicatedFromId(parent.getId())
                .build());
        EstimateItem work = itemRepository.save(EstimateItem.builder()
                .estimate(copy).type(ItemType.WORK).name("Робота").unit(Unit.M2)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal("1300.00"))
                .sourceUnitPrice(new BigDecimal("1000.00"))
                .lineTotal(new BigDecimal("1300.00")).sortOrder(0).build());
        itemRepository.save(EstimateItem.builder()
                .estimate(copy).type(ItemType.WORK).name("Доплата").unit(Unit.PERCENT)
                .quantity(new BigDecimal("20.000"))
                .unitPrice(BigDecimal.ZERO)
                .sourceUnitPrice(new BigDecimal("20.00"))
                .percentBaseKind(PercentBaseKind.POSITION)
                .percentBaseItemId(work.getId())
                .lineTotal(new BigDecimal("260.00"))
                .sortOrder(1).build());

        // The base line earns 1 300 − 1 000 = 300; the percentage line earns 260 − 200 = 60.
        assertThat(estimateRepository.sumWorksCounted(projectId)).isEqualByComparingTo("360.00");
    }
}
