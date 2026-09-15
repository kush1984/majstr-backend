package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V133's two guarantees that outlive the migration itself.
 *
 * <p>A data-only migration proves itself at apply time — its {@code DO $$} self-checks run against
 * the real schema and a green Testcontainers boot IS the assertion. What that cannot do is stop a
 * LATER migration from quietly undoing the work, which is what these tests are for: the corrected
 * figures are read back from the live database, so re-seeding the old ones reddens the build.</p>
 *
 * <p>Review item B-17 is the other half. {@code MaterialCalculatorService#line} divides by
 * {@code package_size} and then labels the answer with the material's {@code unit}, never reading
 * {@code package_unit} — so a material sold in a unit other than its own would be silently
 * mislabelled on the shopping list. V133 makes the agreement a CHECK rather than a coincidence.</p>
 */
class DrywallNormCorrectionsIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    // ---- B-17: packaging is measured in the material's own unit ----------------------------

    @Test
    void aMaterialPackagedInAnotherUnitIsRefused() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name)
                VALUES (?, 'V133_TEST_BAD', 'Тестовий матеріал V133', NULL, 'KG', 10, 'LITRE', 'відро')
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyShippedMaterialPackagesInItsOwnUnit() {
        Integer mismatched = jdbc.queryForObject("""
                SELECT count(*) FROM material WHERE package_unit IS NOT NULL AND package_unit <> unit
                """, Integer.class);

        assertThat(mismatched).isZero();
    }

    /** A size with no unit has nothing to round to, and V126's pair CHECK still holds after V133. */
    @Test
    void aPackageSizeWithoutAUnitIsStillRefused() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name)
                VALUES (?, 'V133_TEST_HALF', 'Тестовий матеріал V133 (б)', NULL, 'KG', 10, NULL, 'мішок')
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- the norm figures ------------------------------------------------------------------

    /**
     * The screw counts V127 shipped were roughly a screw every 100 mm on every rib; the system
     * sheets screw the FIELD of a board at 250 mm. Partition figures are halved on top of that,
     * because the m² the master types on a «перегородки 2 сторони» position is SHEATHING area with
     * both faces already in it (master's ruling, 2026-09-08) — which is why 12 sits beside 14.
     */
    @Test
    void theSheathingScrewFiguresMatchTheDatasheets() {
        assertThat(qty("монтаж гіпсокартону на стіни", "SCREW_TN25"))
                .isEqualByComparingTo("14");
        assertThat(qty("монтаж гіпсокартону на стелю рівну", "SCREW_TN25"))
                .isEqualByComparingTo("20");
        assertThat(qty("монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар", "SCREW_TN25"))
                .isEqualByComparingTo("12");
    }

    /** The inner layer of a two-layer build is only tacked; the outer layer's TN35 does the holding. */
    @Test
    void aTwoLayerBuildBuysFewerShortScrewsThanASingleOne() {
        BigDecimal inner = qty("каркасна звукоізоляція (гкл в два слоя) стелі", "SCREW_TN25");
        BigDecimal outer = qty("каркасна звукоізоляція (гкл в два слоя) стелі", "SCREW_TN35");

        assertThat(inner).isEqualByComparingTo("9");
        assertThat(outer).isEqualByComparingTo("17");
        assertThat(inner).isLessThan(outer);
    }

    /**
     * Every frame we sell is held together by a metal-to-metal screw that no position bought before
     * V133 — the kind of gap a master only finds at the top of a ladder. The figures follow OUR
     * frame (V130's 1,3 wall hangers, and 0,7 ceiling hangers with 1,7 connectors), not a
     * datasheet's, so changing the hangers means recomputing these in the same migration.
     */
    @Test
    void everyFramePositionBuysItsMetalToMetalScrew() {
        assertThat(qty("монтаж гіпсокартону на стіни", "SCREW_LN")).isEqualByComparingTo("3");
        assertThat(qty("монтаж гіпсокартону на стелю рівну", "SCREW_LN")).isEqualByComparingTo("8");
        assertThat(qty("каркасна звукоізоляція (гкл в два слоя) стелі", "SCREW_LN"))
                .isEqualByComparingTo("8");
    }

    /**
     * A norm's unit is the POSITION's unit and nothing is converted (V127 decision 2): the primer is
     * a LITRE material answering a M2 position, so 0,15 means 0,15 litres per square metre. Reading
     * it as litres per litre is the v1 bug this rule exists to prevent.
     */
    @Test
    void glueingBoardToAWallPrimesTheWallFirst() {
        assertThat(qty("монтаж гіпсокартону на клей", "PRIMER_DEEP")).isEqualByComparingTo("0.15");

        String unit = jdbc.queryForObject("""
                SELECT n.unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = 'монтаж гіпсокартону на клей'
                   AND m.code = 'PRIMER_DEEP'
                """, String.class);
        assertThat(unit).isEqualTo("M2");
    }

    /** V131's box норми are SECTION-based, and they take a ceiling's rate per m² of board. */
    @Test
    void theBoxPositionsTookTheCeilingRate() {
        Integer stale = jdbc.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'SCREW_TN25' AND n.basis = 'SECTION'
                   AND n.qty_per_unit NOT IN (20, 28)
                """, Integer.class);

        assertThat(stale).isZero();
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }
}
