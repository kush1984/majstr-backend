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
     *
     * <p>Read off «ґрунтівка поверхні», the standalone priming step, because since V145 that is the
     * only KIND of position that carries a primer norm at all (review B-51).</p>
     */
    @Test
    void aPrimerNormIsLitresPerSquareMetreOfPosition() {
        assertThat(qty("ґрунтівка поверхні", "PRIMER_DEEP")).isEqualByComparingTo("0.15");

        String unit = jdbc.queryForObject("""
                SELECT n.unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = 'ґрунтівка поверхні'
                   AND m.code = 'PRIMER_DEEP'
                """, String.class);
        assertThat(unit).isEqualTo("M2");
    }


    /**
     * V131's box норми are SECTION-based, and they take a ceiling's rate per m² of board — 20 for a
     * straight box and a niche, 28 for a radius one, whose bent face is screwed denser.
     *
     * <p>Asserted PER POSITION. The first version counted rows whose figure was {@code NOT IN
     * (20, 28)}, which passes just as happily with the two values swapped — a radius box at 20 and a
     * straight one at 28 is a real mistake and the guard could not see it (review §3).</p>
     */
    @Test
    void theBoxPositionsTookTheCeilingRate() {
        assertThat(qty("монтаж короба (прямого) із гіпсокартону по периметру стелі", "SCREW_TN25"))
                .isEqualByComparingTo("20");
        assertThat(qty("монтаж ніші під прихований карниз короб під комунікації", "SCREW_TN25"))
                .isEqualByComparingTo("20");
        assertThat(qty("монтаж короба (радіусного) із гіпсокартону по периметру стелі", "SCREW_TN25"))
                .as("a bent face is screwed denser, and that is the only reason it differs")
                .isEqualByComparingTo("28");
    }

    /**
     * The sheathing figures the first version of this test left unpinned (review §3): a sloped
     * ceiling, both partition builds, and the two-layer WALL — the one whose 7/14 pair nothing read.
     */
    @Test
    void everySheathingFigureIsPinned_notJustTheThreeMostObvious() {
        assertThat(qty("монтаж гіпсокартону на стелю зі скосами", "SCREW_TN25"))
                .as("a sloped ceiling is still a ceiling").isEqualByComparingTo("20");
        assertThat(qty("монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари", "SCREW_TN25"))
                .as("the inner layer of a two-layer partition is only tacked").isEqualByComparingTo("5");
        assertThat(qty("монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари", "SCREW_TN35"))
                .isEqualByComparingTo("12");
        assertThat(qty("каркасна звукоізоляція (гкл в два слоя) стін", "SCREW_TN25"))
                .isEqualByComparingTo("7");
        assertThat(qty("каркасна звукоізоляція (гкл в два слоя) стін", "SCREW_TN35"))
                .isEqualByComparingTo("14");
    }

    /**
     * V133 took the joint filler from 0,4 to 0,3 kg per m² of board (Rigips 0,18, Siniat UA 0,25,
     * ready-mixed pastes 0,36 — 0,3 is the middle of the published band), and review §3 asked again
     * for the same figure from the Uniflott/Siniat/Rigips sheets. Nothing read it until now.
     */
    @Test
    void theJointFillerIsTheMiddleOfThePublishedBand() {
        assertThat(qty("шпаклювання швів гкл та шурупів зі шліфуванням", "PUTTY_JOINT"))
                .isEqualByComparingTo("0.3");
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }
}
