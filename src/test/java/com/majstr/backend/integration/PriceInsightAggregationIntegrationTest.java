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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code EstimateItemRepository#aggregatePerMasterWorkPrices}, run as real SQL against real
 * Postgres — a {@code percentile_cont} GROUP BY with a two-table join, none of which a Mockito
 * test can reach (the mock returns whatever it's told, syntax errors and all). This is the
 * per-master half of the community-price two-level median; the cross-master half
 * ({@code PriceInsightMath}) is pure Java and tested separately.
 */
class PriceInsightAggregationIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;
    @Autowired EstimateItemRepository itemRepository;

    private User master(String label) {
        String unique = UUID.randomUUID().toString();
        return userRepository.save(User.builder()
                .email(label + "-" + unique + "@majstr.test")
                .emailCanonical(label + "-" + unique + "@majstr.test")
                .passwordHash("x")
                .fullName(label)
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.PRO)
                .referralCode(unique.substring(0, 10))
                .build());
    }

    private Project objectOf(User owner) {
        return projectRepository.save(Project.builder()
                .owner(owner).name("Обʼєкт").address("вул. Тестова, 1").status(ProjectStatus.DRAFT).build());
    }

    private Estimate estimate(User owner, EstimateStatus status) {
        return estimateRepository.save(Estimate.builder()
                .project(objectOf(owner)).status(status).countInEconomy(true).build());
    }

    private void line(Estimate estimate, ItemType type, Unit unit, String name, String price) {
        itemRepository.save(EstimateItem.builder()
                .estimate(estimate).type(type).name(name).unit(unit)
                .quantity(new BigDecimal("1.000")).unitPrice(new BigDecimal(price))
                .lineTotal(new BigDecimal(price).setScale(2)).sortOrder(0).build());
    }

    @Test
    void onePerMasterPerExactSpelling_withThePerMasterMedian() {
        User m1 = master("Іван");
        // Three of m1's OWN lines under the same literal name — must collapse to ONE row with
        // the median of the three, not three separate rows (that is the whole point of doing
        // this step in SQL before it ever reaches the cross-master fold).
        Estimate e1 = estimate(m1, EstimateStatus.SIGNED);
        line(e1, ItemType.WORK, Unit.M2, "Штукатурка стін", "100.00");
        Estimate e2 = estimate(m1, EstimateStatus.DRAFT);
        line(e2, ItemType.WORK, Unit.M2, "Штукатурка стін", "300.00");
        line(e2, ItemType.WORK, Unit.M2, "Штукатурка стін", "200.00");

        User m2 = master("Петро");
        Estimate e3 = estimate(m2, EstimateStatus.SENT);
        line(e3, ItemType.WORK, Unit.M2, "Штукатурка стін", "500.00");

        List<EstimateItemRepository.PerMasterPriceRow> rows = itemRepository.aggregatePerMasterWorkPrices();
        List<EstimateItemRepository.PerMasterPriceRow> forThisWork = rows.stream()
                .filter(r -> r.getRawKey().equals("штукатурка стін")).toList();

        assertThat(forThisWork).hasSize(2); // one row per master, never per line
        BigDecimal m1Median = forThisWork.stream().filter(r -> r.getMasterId().equals(m1.getId()))
                .findFirst().orElseThrow().getPerMasterMedianPrice();
        BigDecimal m2Median = forThisWork.stream().filter(r -> r.getMasterId().equals(m2.getId()))
                .findFirst().orElseThrow().getPerMasterMedianPrice();
        assertThat(m1Median).isEqualByComparingTo("200.00");
        assertThat(m2Median).isEqualByComparingTo("500.00");
    }

    @Test
    void rejectedEstimatesAreExcluded() {
        User m = master("Рекламація");
        Estimate rejected = estimate(m, EstimateStatus.REJECTED);
        line(rejected, ItemType.WORK, Unit.M2, "Демонтаж унікальний рекламований", "999.00");

        boolean present = itemRepository.aggregatePerMasterWorkPrices().stream()
                .anyMatch(r -> r.getRawKey().equals("демонтаж унікальний рекламований"));

        assertThat(present).isFalse();
    }

    @Test
    void materialsPercentAndZeroPriceLinesAreExcluded() {
        User m = master("Фільтри");
        Estimate e = estimate(m, EstimateStatus.SIGNED);
        line(e, ItemType.MATERIAL, Unit.PIECE, "Фільтровані матеріали унікальні", "150.00");
        itemRepository.save(EstimateItem.builder()
                .estimate(e).type(ItemType.WORK).name("Фільтровані матеріали унікальні")
                .unit(Unit.PERCENT).quantity(new BigDecimal("10.000")).unitPrice(BigDecimal.ZERO)
                .lineTotal(new BigDecimal("15.00")).sortOrder(1).build());
        line(e, ItemType.WORK, Unit.M2, "Фільтровані матеріали унікальні", "0.00");

        boolean present = itemRepository.aggregatePerMasterWorkPrices().stream()
                .anyMatch(r -> r.getRawKey().equals("фільтровані матеріали унікальні"));

        assertThat(present).isFalse();
    }

    @Test
    void aRealWorkLineAtAPositivePriceIsIncluded() {
        User m = master("Контроль");
        Estimate e = estimate(m, EstimateStatus.SIGNED);
        line(e, ItemType.WORK, Unit.M2, "Контрольна позиція унікальна", "777.00");

        boolean present = itemRepository.aggregatePerMasterWorkPrices().stream()
                .anyMatch(r -> r.getRawKey().equals("контрольна позиція унікальна"));

        assertThat(present).isTrue();
    }
}
