package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateItemFromCatalogRequest;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.EstimateTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a line lands, against the catalog the product actually ships.
 *
 * <p>This is the shape of bug Mockito cannot see, because both halves of it are DATA. V118 stores
 * ONE {@code catalog_items} row per (owner, name, type, unit), under whichever of the master's
 * trades claimed the name first; the shipped library files that same name under several trades,
 * each with its OWN folder. So the defect only exists where a real catalog meets a real bundle:
 * a PAINTER bundle produced «Шпаклювання фінішне» under DRYWALL / «Оздоблення під фарбування» and
 * «Прибирання приміщення після робіт» under TILING / «Організаційні послуги», and the master saw
 * «якісь не зрозумілі категорії з плитки, гіпсокартону» on a painting estimate.</p>
 *
 * <p>It is not cosmetic: the same stamp is what {@code MaterialCalculatorService#normsFor} filters
 * consumption norms by, so a foreign trade also costs the line its own materials — on the master's
 * live catalog «Фарбування фасаду» filed under BUILDER could not reach the facade norms V138 had
 * just shipped under PAINTER.</p>
 */
class LineFilingOnLiveCatalogIntegrationTest extends IntegrationTestBase {

    /** A position PAINTER and DRYWALL both ship, filed in a different folder by each. */
    private static final String SHARED = "Шпаклювання фінішне (2–4 рази)";
    /** ...and one PAINTER and TILING both ship. */
    private static final String ORGANISATIONAL = "Прибирання приміщення після робіт";

    @Autowired JdbcTemplate jdbc;
    @Autowired EstimateTemplateService templateService;
    @Autowired EstimateService estimateService;

    private UUID ownerId;
    private UUID projectId;
    private UUID bundleId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'PAINTER')", ownerId);

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        // The master's own catalog, exactly as V118 leaves it: the shared positions are stored
        // under the OTHER trade, because that is the one that claimed the name first.
        storedUnder(SHARED, Trade.DRYWALL, "Оздоблення під фарбування", "180.00");
        storedUnder(ORGANISATIONAL, Trade.TILING, "Організаційні послуги", "500.00");

        bundleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES (?, NULL, 'Малярні роботи — тест', 'PAINTER', TRUE)
                """, bundleId);
        bundleItem(SHARED, 0);
        bundleItem(ORGANISATIONAL, 1);
    }

    private void storedUnder(String name, Trade trade, String category, String price) {
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source)
                VALUES (?, ?, ?, ?, 'WORK', 'M2', ?::numeric, ?, 'LIBRARY')
                """, UUID.randomUUID(), ownerId, name, category, price, trade.name());
    }

    private void bundleItem(String name, int sort) {
        jdbc.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (?, ?, ?, 'WORK', 'M2', ?)
                """, UUID.randomUUID(), bundleId, name, sort);
    }

    private List<Map<String, Object>> linesOf(UUID estimateId) {
        return jdbc.queryForList(
                "SELECT name, trade, category, unit_price FROM estimate_items "
                        + "WHERE estimate_id = ? ORDER BY sort_order", estimateId);
    }

    /**
     * The whole complaint, end to end: apply a PAINTER bundle and no line may carry another
     * trade's name on its folder.
     */
    @Test
    void aPainterBundleFilesEveryLineUnderPainter_whateverTradeStoresThePosition() {
        var estimate = templateService.applyToProject(projectId, bundleId,
                new EstimateCreateRequest(null, null, "Кімната"), ownerId);

        List<Map<String, Object>> lines = linesOf(estimate.id());
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line ->
                assertThat(line.get("trade")).as("%s", line.get("name")).isEqualTo("PAINTER"));

        // ...and in the folder PAINTER itself files them under, which is not the stored one.
        assertThat(lines.get(0).get("category")).isEqualTo("Шпаклювання та шліфування");
        assertThat(lines.get(1).get("category")).isEqualTo("Організаційні послуги");
        // Only the filing moved. The price is still the master's own, off the row that stores it.
        assertThat((BigDecimal) lines.get(0).get("unit_price")).isEqualByComparingTo("180.00");
    }

    /**
     * The other door. The picker tree shows a shared position under EVERY trade that ships it, so
     * tapping it inside «Малярні роботи» must not produce a drywall line.
     */
    @Test
    void pickingFromTheCatalogFilesTheLineUnderTheBranchItWasTappedIn() {
        UUID estimateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, 'Кошторис', 'DRAFT')
                """, estimateId, projectId);
        UUID catalogId = jdbc.queryForObject(
                "SELECT id FROM catalog_items WHERE owner_id = ? AND name = ?",
                UUID.class, ownerId, SHARED);

        estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0, Trade.PAINTER),
                ownerId, null);

        assertThat(linesOf(estimateId)).singleElement().satisfies(line -> {
            assertThat(line.get("trade")).isEqualTo("PAINTER");
            assertThat(line.get("category")).isEqualTo("Шпаклювання та шліфування");
        });
    }

    /** No trade in hand — the autocomplete, an offline replay — leaves the stored filing alone. */
    @Test
    void addingWithNoBranchInHandKeepsTheStoredFiling() {
        UUID estimateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, 'Кошторис', 'DRAFT')
                """, estimateId, projectId);
        UUID catalogId = jdbc.queryForObject(
                "SELECT id FROM catalog_items WHERE owner_id = ? AND name = ?",
                UUID.class, ownerId, SHARED);

        estimateService.addItemFromCatalog(estimateId, catalogId,
                new EstimateItemFromCatalogRequest(new BigDecimal("12"), 0), ownerId, null);

        assertThat(linesOf(estimateId)).singleElement().satisfies(line -> {
            assertThat(line.get("trade")).isEqualTo("DRYWALL");
            assertThat(line.get("category")).isEqualTo("Оздоблення під фарбування");
        });
    }

    /**
     * The two positions this test is built on must stay shared, and stay filed differently by each
     * trade — otherwise the tests above would pass by saying nothing. A catalog rebuild that
     * re-files or renames either one should redden HERE, with this sentence, rather than quietly
     * turn three assertions into tautologies.
     */
    @Test
    void theLibraryStillShipsTheseTwoPositionsUnderTwoTradesWithDifferentFolders() {
        assertThat(folderOf(Trade.PAINTER, SHARED)).isEqualTo("Шпаклювання та шліфування");
        assertThat(folderOf(Trade.DRYWALL, SHARED)).isEqualTo("Оздоблення під фарбування");
        assertThat(folderOf(Trade.PAINTER, ORGANISATIONAL)).isEqualTo("Організаційні послуги");
        assertThat(folderOf(Trade.TILING, ORGANISATIONAL)).isEqualTo("Організаційні послуги");
    }

    private String folderOf(Trade trade, String name) {
        return jdbc.queryForObject("""
                SELECT category FROM catalog_templates
                 WHERE trade = ? AND type = 'WORK' AND unit = 'M2'
                   AND lower(btrim(regexp_replace(name, '\\s+', ' ', 'g'))) = lower(btrim(?))
                """, String.class, trade.name(), name);
    }
}
