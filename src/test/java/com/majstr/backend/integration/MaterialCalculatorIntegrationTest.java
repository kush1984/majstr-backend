package com.majstr.backend.integration;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialAvailabilityResponse;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.MaterialLineRequest;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.MaterialCalculatorService;
import com.majstr.backend.service.ShoppingListService;
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
 * V127 — the calculator against the catalog it is actually keyed to.
 *
 * <p>Every norm is joined to a position by NAME and UNIT alone, so the whole feature rests on 44
 * strings agreeing with 44 other strings in a different migration. Nothing in Java can check that:
 * a typo, a swapped hyphen for an en-dash, or a position renamed in a later rebuild produces no
 * error at all — just a position that quietly proposes nothing. The migration's own self-check
 * catches a norm pointing at a position that does not exist; these tests catch the other
 * direction, and the arithmetic that the mocked unit tests can only assert about themselves.</p>
 */
class MaterialCalculatorIntegrationTest extends IntegrationTestBase {

    /** The same normalisation {@code NameKeys.of} performs, in SQL — see the V127 self-check. */
    private static final String NAME_KEY =
            "lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))";

    /** A position no shipped norm names — the point is that nothing anywhere answers for it. */
    private static final String UNKNOWN_WORK = "Робота якої немає в нормах";

    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialCalculatorService calculatorService;
    @Autowired ShoppingListService shoppingListService;

    private UUID ownerId;
    private UUID projectId;
    private UUID estimateId;
    private int sortOrder;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));

        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);

        estimateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status)
                VALUES (?, ?, 'Кошторис', 'DRAFT')
                """, estimateId, projectId);
        sortOrder = 0;
    }

    // --- the join the whole feature rests on ------------------------------------------------

    /**
     * The join checked from both ends, then the whole shipped DRYWALL catalog run through the
     * calculator in one estimate.
     *
     * <p>The two SQL invariants are the load-bearing half, and V127's own self-check cannot replace
     * them: that check ran when V127 applied, while the failure this guards against arrives with a
     * LATER rebuild renaming a position — the norm then answers for nothing, with no error
     * anywhere. The second query catches the other data conflict: a (name_key, unit) two trades
     * both norm is unresolvable for a position that names no trade of its own, so it is skipped.</p>
     */
    @Test
    void everyDrywallNormFindsItsPositionInTheShippedCatalog() {
        List<String> orphaned = jdbc.queryForList("""
                SELECT n.name_key || ' [' || n.unit || ']' FROM material_norm n
                 WHERE n.owner_id IS NULL AND n.trade = 'DRYWALL'
                   AND NOT EXISTS (SELECT 1 FROM catalog_templates t
                                    WHERE t.trade = 'DRYWALL' AND t.type = 'WORK'
                                      AND t.unit = n.unit
                                      AND""" + " " + NAME_KEY + " = n.name_key)", String.class);
        assertThat(orphaned).as("norms whose position the catalog no longer ships").isEmpty();

        List<String> ambiguous = jdbc.queryForList("""
                SELECT name_key || ' [' || unit || ']' FROM material_norm
                 WHERE owner_id IS NULL
                 GROUP BY name_key, unit HAVING count(DISTINCT trade) > 1
                """, String.class);
        assertThat(ambiguous).as("a name and unit two trades both norm cannot be resolved").isEmpty();

        List<Map<String, Object>> templates = jdbc.queryForList("""
                SELECT t.name, t.unit FROM catalog_templates t
                 WHERE t.trade = 'DRYWALL' AND t.type = 'WORK'
                """);
        assertThat(templates).as("shipped DRYWALL positions").isNotEmpty();
        templates.forEach(t -> addWork((String) t.get("name"), (String) t.get("unit"), "10"));

        MaterialCalculationResponse result = calculate(null, null);

        assertThat(result.coverage().trades()).containsExactly("DRYWALL");
        assertThat(result.materials()).as("the whole catalog must buy something").isNotEmpty();
    }

    /** A percent surcharge is not work: it can never have a norm, so it names no trade. */
    @Test
    void aPercentSurchargeNamesNoTradeOfItsOwn() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "20");
        addWork("Робота на висоті", "PERCENT", "10");

        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);

        assertThat(result.coverage().trades()).containsExactly("DRYWALL");
    }

    /**
     * Demolition buys nothing, and V127 says so on the record rather than staying silent — the 11
     * {@code material_id IS NULL} verdicts are still observable: the position is COUNTED (its trade
     * is named) and proposes nothing.
     */
    @Test
    void aWorkThatConsumesNothingIsCountedWithoutProposingAnything() {
        addWork(name("демонтаж перегородки з гіпсокартону", "M2"), "M2", "18");

        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);

        assertThat(result.coverage().trades()).containsExactly("DRYWALL");
        assertThat(result.materials()).isEmpty();
    }

    /**
     * A price-list row («Штукатурні роботи (від) — 0 м²») is not a decision to buy anything. It used
     * to reach the norms and produce rows like «Лист ГКЛ — 0 м²», which is what «звідки у матеріалах
     * стільки матеріалів» was about (master, 2026-09-11).
     */
    @Test
    void aRowWithNoQuantityBuysNothingAndIsNotOfferedTheScreen() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "0");

        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);

        assertThat(result.materials()).isEmpty();
        assertThat(result.coverage().trades()).isEmpty();
        assertThat(calculatorService.availability(estimateId, ownerId).available()).isFalse();
    }

    // --- the availability probe: an absent answer is HIDDEN, never shown empty ---------------

    /**
     * V127 norms DRYWALL and nothing else. A tiler opening the materials screen would get an empty
     * buying list, which reads as a broken feature rather than an absent one — so the entry point
     * is not offered at all.
     */
    @Test
    void anEstimateNothingCanBeCalculatedForIsNotOfferedTheScreen() {
        addWork(UNKNOWN_WORK, "M2", "40");

        MaterialAvailabilityResponse availability = calculatorService.availability(estimateId, ownerId);

        assertThat(availability.available()).isFalse();
    }

    /**
     * «Consumes nothing» is a complete answer for the coverage line and NO answer for the button.
     * The master's estimate had one drywall position, a demolition one; every norm for it carries
     * {@code material_id IS NULL}, so «Матеріали» opened a screen with nothing on it. The trade is
     * still counted — the work was checked, it just buys nothing.
     */
    @Test
    void aConsumesNothingVerdictIsNotAnAnswerForOfferingTheScreen() {
        addWork(name("демонтаж перегородки з гіпсокартону", "M2"), "M2", "18");

        assertThat(calculatorService.availability(estimateId, ownerId).available()).isFalse();
        assertThat(calculate(BigDecimal.ZERO, null).coverage().trades()).containsExactly("DRYWALL");
    }

    /** The probe and the calculation share one lookup, so they can never disagree about this. */
    @Test
    void theProbeAgreesWithWhatTheCalculationCounted() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "20");
        addWork(UNKNOWN_WORK, "M2", "40");

        MaterialAvailabilityResponse availability = calculatorService.availability(estimateId, ownerId);
        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);

        assertThat(availability.available()).isTrue();
        assertThat(result.coverage().trades()).containsExactly("DRYWALL");
        assertThat(result.materials()).isNotEmpty();
    }

    // --- the arithmetic, on real norms -------------------------------------------------------

    /**
     * 20 m² of partition in one layer. The master typed the area of the whole partition, both faces
     * — the ruling that decides this feature — so it is 20 m² of board, not 40.
     */
    @Test
    void aPartitionBuysBoardForTheAreaTheMasterTypedAndNotTwice() {
        addWork(name("монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар", "M2"),
                "M2", "20");

        CalculatedMaterialLine board = line(calculate(BigDecimal.ZERO, null), "Лист ГКЛ");

        assertThat(board.baseQuantity()).isEqualByComparingTo("20");
        assertThat(board.packageName()).isEqualTo("лист");
        assertThat(board.packages()).isEqualTo(7); // 20 / 3,0 m², rounded UP
        assertThat(board.quantity()).isEqualByComparingTo("21");
    }

    /** The two-layer position is the same area twice over — stated by the position, not guessed. */
    @Test
    void theTwoLayerPositionBuysTwiceTheBoard() {
        addWork(name("монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари", "M2"),
                "M2", "20");

        assertThat(line(calculate(BigDecimal.ZERO, null), "Лист ГКЛ").baseQuantity())
                .isEqualByComparingTo("40");
    }

    /** A per-м.п. position multiplies a per-м.п. norm. The first draft converted, and bought 30×. */
    @Test
    void aLinearMetrePositionStaysInLinearMetres() {
        addWork(name("монтаж треків прихованого карниза", "LINEAR_METER"), "LINEAR_METER", "12");

        CalculatedMaterialLine track = line(calculate(BigDecimal.ZERO, null), "Трек");

        assertThat(track.unit()).isEqualTo(Unit.LINEAR_METER);
        assertThat(track.baseQuantity()).isEqualByComparingTo("12.6");
        assertThat(track.quantity()).isEqualByComparingTo("13");
    }

    @Test
    void theWasteAllowanceIsAppliedOnTopOfTheNorm() {
        addWork(name("утеплення мінватою в один шар", "M2"), "M2", "100");

        CalculatedMaterialLine wool = line(calculate(new BigDecimal("15"), null), "Мінеральна вата");

        assertThat(wool.baseQuantity()).isEqualByComparingTo("105");
        assertThat(wool.quantity()).isEqualByComparingTo("121"); // 105 × 1,15 = 120,75, up
    }

    /** Two positions, one material: the shop trip is one line, and it says where it came from. */
    @Test
    void twoPositionsWantingBoardBecomeOneRowThatExplainsItself() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "20");
        addWork(name("монтаж гіпсокартону на стелю рівну", "M2"), "M2", "15");

        CalculatedMaterialLine board = line(calculate(BigDecimal.ZERO, null), "Лист ГКЛ");

        assertThat(board.baseQuantity()).isEqualByComparingTo("35");
        assertThat(board.sources()).hasSize(2);
    }

    // --- the perimeter parameter --------------------------------------------------------------

    /**
     * A UD track runs around the room, and an area does not tell us how long that is. The engine
     * asks; it does not derive a perimeter from 15 m² of ceiling and quietly buy the answer.
     */
    @Test
    void theTrackIsAskedForRatherThanDerivedFromTheArea() {
        addWork(name("монтаж гіпсокартону на стелю рівну", "M2"), "M2", "15");

        MaterialCalculationResponse without = calculate(BigDecimal.ZERO, null);
        assertThat(without.parameters()).extracting("parameter").containsOnly("PERIMETER");
        assertThat(without.materials()).extracting(CalculatedMaterialLine::name)
                .noneMatch(n -> n.startsWith("Профіль UD"));

        MaterialCalculationResponse with = calculate(BigDecimal.ZERO, new BigDecimal("16"));
        assertThat(with.parameters()).isEmpty();
        assertThat(line(with, "Профіль UD").baseQuantity()).isEqualByComparingTo("16.8");
    }

    /** One room, one perimeter: a wall lining beside a ceiling must not buy the track twice. */
    @Test
    void aCeilingAndAWallLiningShareTheOnePerimeter() {
        addWork(name("монтаж гіпсокартону на стелю рівну", "M2"), "M2", "15");
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "30");

        // The wall норм (2,1 м/м of perimeter) is the larger of the two and wins outright.
        assertThat(line(calculate(BigDecimal.ZERO, new BigDecimal("16")), "Профіль UD").baseQuantity())
                .isEqualByComparingTo("33.6");
    }

    // --- the outputs ---------------------------------------------------------------------------

    @Test
    void sendingTheSameCalculationTwiceDoesNotDoubleTheShoppingList() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "20");
        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);
        MaterialApplyRequest request = new MaterialApplyRequest(result.materials().stream()
                .map(m -> new MaterialLineRequest(m.materialId(), m.quantity()))
                .toList());

        calculatorService.toShoppingList(estimateId, ownerId, request);
        ShoppingListResponse after = calculatorService.toShoppingList(estimateId, ownerId, request);

        assertThat(after.items()).extracting(ShoppingListItemResponse::name)
                .doesNotHaveDuplicates();
        assertThat(after.items()).filteredOn(i -> i.name().startsWith("Лист ГКЛ"))
                .singleElement()
                .satisfies(i -> assertThat(i.quantity()).isEqualByComparingTo("21"));
    }

    @Test
    void theShoppingListRowsCarryTheDictionarySpecAndUnit() {
        addWork(name("монтаж гіпсокартону на стіни", "M2"), "M2", "20");
        MaterialCalculationResponse result = calculate(BigDecimal.ZERO, null);

        calculatorService.toShoppingList(estimateId, ownerId, new MaterialApplyRequest(
                result.materials().stream()
                        .map(m -> new MaterialLineRequest(m.materialId(), m.quantity()))
                        .toList()));

        ShoppingListResponse list = shoppingListService.get(projectId, ownerId);
        assertThat(list.items()).filteredOn(i -> i.name().startsWith("Лист ГКЛ"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.name()).isEqualTo("Лист ГКЛ 1200×2500");
                    assertThat(i.unit()).isEqualTo(Unit.M2);
                });
    }

    // --- the reason norms are keyed by name ----------------------------------------------------

    /**
     * Every catalog rebuild (V82, V116, V122) deletes and recreates {@code catalog_templates}, so a
     * norm holding a template id would be orphaned by the next content change — the bug that killed
     * the second draft. The invariant is structural: nothing in {@code material_norm} may reference
     * the catalog at all.
     */
    @Test
    void nothingInTheNormTableReferencesTheCatalog() {
        List<String> referenced = jdbc.queryForList("""
                SELECT DISTINCT ccu.table_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                   AND ccu.constraint_schema = tc.constraint_schema
                 WHERE tc.table_name = 'material_norm' AND tc.constraint_type = 'FOREIGN KEY'
                """, String.class);

        assertThat(referenced)
                .as("a norm outlives every catalog rebuild only because it points at no catalog row")
                .doesNotContain("catalog_templates", "catalog_items", "estimate_templates",
                        "estimate_template_items");
    }

    // -------------------------------------------------------------------------------------------

    private MaterialCalculationResponse calculate(BigDecimal waste, BigDecimal perimeter) {
        return calculatorService.calculate(estimateId, ownerId, waste, perimeter, null);
    }

    private CalculatedMaterialLine line(MaterialCalculationResponse result, String namePrefix) {
        List<CalculatedMaterialLine> matching = result.materials().stream()
                .filter(m -> m.name().startsWith(namePrefix))
                .toList();
        assertThat(matching).as("calculated rows starting with %s", namePrefix).hasSize(1);
        return matching.get(0);
    }

    /** The position's name as the catalog actually ships it — the string the norm has to match. */
    private String name(String nameKey, String unit) {
        List<String> names = jdbc.queryForList("""
                SELECT t.name FROM catalog_templates t
                 WHERE t.trade = 'DRYWALL' AND t.type = 'WORK' AND t.unit = ?
                   AND """ + " " + NAME_KEY + " = ?", String.class, unit, nameKey);
        assertThat(names).as("shipped DRYWALL position %s [%s]", nameKey, unit).hasSize(1);
        return names.get(0);
    }

    private void addWork(String name, String unit, String quantity) {
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade)
                VALUES (?, ?, 'WORK', ?, ?, ?::numeric, 100, 100, ?, 'DRYWALL')
                """, UUID.randomUUID(), estimateId, name, unit, quantity, sortOrder++);
    }
}
