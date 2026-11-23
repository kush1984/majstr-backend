package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.PercentBaseKind;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.EstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * «% від позицій» (V221, owner 2026-11-23: «тут треба дозволяти вибирати з декількох позицій») — a
 * percentage line measured against several lines of its estimate, through every door that keeps it:
 * save, an older build's single id, a deleted base, and a markup copy.
 */
class PercentOfSeveralPositionsIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired EstimateService estimateService;

    private UUID ownerId;
    private UUID estimateId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, plan)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 'PRO')
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        UUID projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
        estimateId = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Ванна', 'DRAFT')",
                estimateId, projectId);
    }

    private UUID work(String name, String quantity, String price) {
        return estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, name, null, Unit.M2, new BigDecimal(quantity), new BigDecimal(price),
                null, null, false, null, null), ownerId).id();
    }

    private EstimateItemResponse percentOf(String percent, List<UUID> bases) {
        return estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, "Складність", null, Unit.PERCENT, new BigDecimal(percent), BigDecimal.ZERO,
                null, null, false, PercentBaseKind.POSITION, null, null, null, bases), ownerId);
    }

    private EstimateItemResponse line(EstimateResponse estimate, String name) {
        return estimate.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aPercentOfTwoLinesIsAShareOfTheirSum_inTheOrderTicked() {
        UUID small = work("Укладання плитки 100x100", "10", "800");   // 8 000
        UUID large = work("Укладання плитки 60x60", "20", "500");     // 10 000

        EstimateItemResponse complexity = percentOf("25", List.of(large, small));

        assertThat(complexity.lineTotal()).isEqualByComparingTo("4500.00");
        assertThat(complexity.percentBaseItemIds()).containsExactly(large, small);
        assertThat(complexity.baseDetached()).isFalse();
    }

    @Test
    void aBaseThatIsAPercentOrOfAnotherEstimateIsDropped_andAnOlderBuildsSingleIdStillWorks() {
        UUID tiles = work("Укладання плитки", "10", "1000");
        UUID other = percentOf("10", List.of(tiles)).id();

        EstimateItemResponse complexity = percentOf("20", List.of(tiles, other, UUID.randomUUID()));
        assertThat(complexity.percentBaseItemIds()).containsExactly(tiles);
        assertThat(complexity.lineTotal()).isEqualByComparingTo("2000.00");

        EstimateItemResponse legacy = estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, "Доставка", null, Unit.PERCENT, new BigDecimal("5"), BigDecimal.ZERO,
                null, null, false, PercentBaseKind.POSITION, tiles), ownerId);
        assertThat(legacy.percentBaseItemIds()).containsExactly(tiles);
        assertThat(legacy.percentBaseItemId()).isEqualTo(tiles);
        assertThat(legacy.lineTotal()).isEqualByComparingTo("500.00");
    }

    @Test
    void deletingOneBaseDetachesTheLine_andItKeepsItsAmount() {
        UUID small = work("Укладання плитки 100x100", "10", "800");
        UUID large = work("Укладання плитки 60x60", "20", "500");
        UUID complexity = percentOf("25", List.of(small, large)).id();

        estimateService.deleteItem(estimateId, small, ownerId);

        EstimateItemResponse after = line(estimateService.get(estimateId, ownerId), "Складність");
        assertThat(after.id()).isEqualTo(complexity);
        assertThat(after.baseDetached()).isTrue();
        assertThat(after.lineTotal()).isEqualByComparingTo("4500.00");
    }

    /**
     * A markup copy that raises ONE of two bases: the percent is re-set so the copy charges exactly
     * the parent's amount × 1.2, neither doubling the markup on the raised base nor missing it on the
     * other.
     */
    @Test
    void aMarkupCopyRaisingOneOfTwoBasesChargesTheParentsAmountTimesTheFactor() {
        UUID walls = work("Стіни", "100", "100");   // 10 000, marked up
        work("Стеля", "100", "100");                 // 10 000, at cost
        EstimateItemResponse complexity = percentOf("10", List.of(walls,
                line(estimateService.get(estimateId, ownerId), "Стеля").id()));
        assertThat(complexity.lineTotal()).isEqualByComparingTo("2000.00");

        EstimateResponse copy = estimateService.duplicate(estimateId, new EstimateDuplicateRequest(
                null, new BigDecimal("20"), false, List.of(walls, complexity.id())), ownerId);

        EstimateItemResponse copied = line(copy, "Складність");
        assertThat(copied.lineTotal()).isCloseTo(new BigDecimal("2400"), within(new BigDecimal("0.05")));
        assertThat(copied.percentBaseItemIds())
                .containsExactly(line(copy, "Стіни").id(), line(copy, "Стеля").id());
        assertThat(copied.baseDetached()).isFalse();
    }
}
