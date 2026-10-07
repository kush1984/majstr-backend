package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V147 — the trade-by-trade review's batch 2: FLOORING's first material norms, read back off the
 * applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once. What it cannot do is stop a
 * later migration from quietly putting a figure back, re-filing a shared position under one trade
 * again, or leaving a position behind when the catalog grows — which is what the assertions here are
 * for. The coverage one is the load-bearing test: FLOORING shipped 54 positions and ZERO norms, and
 * V138 settled that partial coverage is worse than none, because the «Матеріали» screen switches ON
 * and offers one line out of forty.</p>
 */
class FlooringNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    /** Everything V147 added to the dictionary. */
    private static final List<String> NEW_CODES = List.of(
            "LAMINATE", "SPC_VINYL", "LVT_VINYL", "FLOOR_ROLL", "FLOOR_BOARD", "PARQUET_BLOCK",
            "DECK_BOARD", "UNDERLAY_LAMINATE", "UNDERLAY_SPC", "PE_FILM", "MESH_SCREED",
            "EXPANDED_CLAY", "DAMPER_TAPE", "PARQUET_ADHESIVE", "ADHESIVE_FLOOR_DISP",
            "MOUNTING_ADHESIVE", "PARQUET_PRIMER", "PARQUET_LACQUER", "PARQUET_FILLER", "DECK_OIL",
            "EPOXY_PRIMER", "EPOXY_COATING", "PU_TOPCOAT", "SKIRTING_PVC", "SKIRTING_MDF",
            "SKIRTING_WOOD", "SKIRTING_FOAM", "SKIRTING_ALU", "HIDDEN_SKIRTING_PROFILE",
            "HIDDEN_SKIRTING_INSERT", "SKIRTING_CLIP", "CORK_STRIP", "THRESHOLD_PROFILE",
            "TIMBER_JOIST", "WOOD_SCREW", "DECK_CLIP");

    /** The dictionary rows V147 reuses instead of adding a second row for the same goods. */
    private static final List<String> REUSED_CODES = List.of(
            "PRIMER_DEEP", "SELF_LEVELLING", "SCREED_CEMENT", "XPS_BOARD", "MINERAL_WOOL",
            "DOWEL_NAIL", "ANCHOR_WEDGE", "SEALANT_SILICONE", "SEALANT_ACRYLIC", "ENAMEL_WOOD");

    /** The positions BUILDER ships too, normed at {@code trade = NULL} so one norm answers both. */
    private static final List<String> SHARED_WITH_BUILDER = List.of(
            "монтаж терасної дошки", "фарбування терасної дошки",
            "армування підлоги сіткою під стяжку", "вирівнювання підлоги самовирівнюючим розчином",
            "цементно-піщана стяжка понад 20м2");

    @Autowired JdbcTemplate jdbc;

    // ---- coverage: the whole trade, or the screen is worse than silent ------------------------

    /**
     * Every FLOORING position now has an answer except the two the review itself could not answer,
     * and those two are named rather than counted — a third one appearing means a position fell out
     * of the set by accident, which is exactly the half-covered screen V138 ruled against.
     */
    @Test
    void everyFlooringPositionIsAnsweredExceptTheTwoThatCannotBe() {
        assertThat(jdbc.queryForList("""
                SELECT t.name || ' [' || t.unit || ']' FROM catalog_templates t
                 WHERE t.trade = 'FLOORING' AND t.type = 'WORK'
                   AND NOT EXISTS (
                        SELECT 1 FROM material_norm n
                         WHERE n.owner_id IS NULL
                           AND (n.trade = 'FLOORING' OR n.trade IS NULL)
                           AND n.unit = t.unit
                           AND n.name_key = lower(btrim(replace(replace(
                                   regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))
                   )
                """, String.class)).containsExactlyInAnyOrder(
                        // Either a semi-dry mix of three materials the dictionary does not hold, or
                        // a machine-poured anhydrite floor. Two products behind one name is a rename
                        // first and a norm second.
                        "Машинна стяжка самовирівнююча [M2]",
                        // The niche profile and its filler belong to a system we do not name.
                        "Монтаж та виготовлення ніші під плінтус прихованого монтажу [LINEAR_METER]");
    }

    /** V147 inserts no catalog position, deliberately: one would force V118's ranking to be re-run
     *  verbatim, which is a batch of its own. V148 was that batch: its bundles needed 14 more, and
     *  each of them is answered too (the test above). */
    @Test
    void theCatalogGrewOnlyByTheFourteenTheBundlesNeed() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates WHERE trade = 'FLOORING' AND type = 'WORK'
                """)).isEqualTo(54 + 14);
    }

    /** «Checked, consumes nothing» is a RECORDED verdict and needs BOTH nulls together (V127's
     *  `material_norm_qty_check`) — abrasive belts and a vacuum cleaner are tool wear, not a
     *  material bought per square metre. */
    @Test
    void theSevenToollessPositionsRecordAVerdictRatherThanStaySilent() {
        List<String> verdicts = jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'FLOORING'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                 ORDER BY name_key
                """, String.class);

        assertThat(verdicts).containsExactlyInAnyOrder(
                "шліфування бетонної основи", "шліфування бетону стяжки",
                "шліфування дерев'яної підлоги", "брашування паркету",
                "підготовка поверхні (очищення і т.п.)", "чистка підлоги порохотягом підготовка",
                "штроблення в стяжці під монтаж перегородок",
                // V148: trimming a door frame for the new covering is the eighth.
                "підрізання дверних коробок і наличників під покриття");
    }

    // ---- the waste decision -------------------------------------------------------------------

    /**
     * The review asked for {@code waste_percent = 5} on every covering, because the old bucket
     * applied the MAXIMUM allowance in it to everything in it. V146 fixed that in the engine, and
     * with the bug gone a shipped 5 would do harm instead: a norm's own allowance OVERRIDES the
     * master's {@code WASTE_PERCENT} habit, so it would silence the figure he set himself on the one
     * material where his own cutting habit matters most.
     */
    @Test
    void noFlooringNormOverridesTheMastersOwnCuttingAllowance() {
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND (trade = 'FLOORING' OR trade IS NULL)
                   AND waste_percent <> 0
                """)).isZero();
    }

    /**
     * What DOES belong in the coefficient is the LAYOUT surplus, because it is a property of the
     * pattern and not of the master's hand: a diagonal course needs ~5 % more plank however
     * carefully it is cut. The underlay is butt-jointed and does not care which way the planks run,
     * so it stays at 1,00 in both — which is the half that would regress silently if someone
     * "tidied" the pair into one figure.
     */
    @Test
    void theDiagonalLayoutCostsPlankAndNotUnderlay() {
        assertThat(qty("укладка ламінату проста", "LAMINATE")).isEqualByComparingTo("1.0");
        assertThat(qty("укладка ламінату по діагоналі", "LAMINATE")).isEqualByComparingTo("1.05");

        for (String position : List.of("укладка ламінату проста", "укладка ламінату по діагоналі")) {
            assertThat(qty(position, "UNDERLAY_LAMINATE")).as("%s", position)
                    .isEqualByComparingTo("1.0");
        }
    }

    // ---- the shared positions ------------------------------------------------------------------

    /**
     * V118's seed array lists BUILDER before FLOORING, so for a master who has both trades these
     * rows land in his catalog under BUILDER — and a FLOORING-filed norm would never be reached.
     * V137's answer is a trade-less norm, which answers for whichever trade the line ended up under
     * and touches none of his own copies; the review's V132-style re-filing of `catalog_items` would
     * have.
     */
    @Test
    void thePositionsBuilderShipsTooAreNormedWithoutATrade() {
        for (String position : SHARED_WITH_BUILDER) {
            assertThat(count("""
                    SELECT count(*) FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND trade IS NOT NULL
                    """, position)).as("%s is still filed under a trade", position).isZero();

            assertThat(count("""
                    SELECT count(*) FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND trade IS NULL
                    """, position)).as("%s has a trade-less norm", position).isPositive();
        }
    }

    /** And the reason they are shared is a fact about the library, not an assumption: each really is
     *  shipped by BUILDER as well. If that ever stops being true the trade-less filing is still
     *  correct, but the comment explaining it is not. */
    @Test
    void thoseFivePositionsReallyAreShippedByTwoTrades() {
        for (String position : SHARED_WITH_BUILDER) {
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT trade FROM catalog_templates
                     WHERE type = 'WORK'
                       AND lower(btrim(replace(replace(
                               regexp_replace(name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = ?
                    """, String.class, position)).as("%s", position)
                    .contains("BUILDER", "FLOORING");
        }
    }

    /** Two trades norming one (name, unit) cannot be resolved — the answer would be a coin flip. A
     *  trade-less row sitting BESIDE a traded one is the same ambiguity with a NULL in it. */
    @Test
    void noPositionIsNormedByTwoTradesAtOnce() {
        assertThat(jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL
                 GROUP BY name_key, unit
                HAVING count(DISTINCT trade) > 1
                    OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))
                """, String.class)).isEmpty();
    }

    // ---- the figures that disagree with a shipped one on purpose -------------------------------

    /**
     * A floor drinks more primer than a puttied wall: Kreisel 375 is 0,15-0,2 l/m² on a cement-sand
     * base and ≥ 0,3 undiluted on concrete, so the floor positions take the top of the band while
     * PAINTER's wall figure stays where V137 put it. Two substrates, not a disagreement — unlike
     * V146's stone-cladding bed, where the substrate was identical and a third number WOULD have
     * been one.
     */
    @Test
    void aFloorIsPrimedAtItsOwnRateAndTheWallKeepsIts() {
        for (String position : List.of("грунтовка підлоги підготовчі роботи", "грунтовка під стяжку")) {
            assertThat(qty(position, "PRIMER_DEEP")).as("%s", position).isEqualByComparingTo("0.2");
        }

        assertThat(qty("грунтування", "PRIMER_DEEP"))
                .as("the standalone priming position V145 ruled on").isEqualByComparingTo("0.15");
        assertThat(qty("грунтовка поверхонь перед шпаклівкою фарбуванням", "PRIMER_DEEP"))
                .as("PAINTER's wall").isEqualByComparingTo("0.15");
    }

    /**
     * An 8×6 mm expansion gap along a wall holds ~48 ml of acrylic per metre; PAINTER's 0,025 is a
     * 5×5 bead in a crack. V146 §3 settled that one figure for every geometry is the bug and not the
     * fix, so the gap gets its own — and the painter's keeps his.
     */
    @Test
    void anExpansionGapIsADifferentSectionFromAPaintersBead() {
        for (String position : List.of("підрізка ламінату защільнення",
                "підрізка ламінату (без плінтуса), защільнення")) {
            assertThat(qty(position, "SEALANT_ACRYLIC")).as("%s", position)
                    .isEqualByComparingTo("0.05");
        }

        assertThat(qty("акрилення примикань", "SEALANT_ACRYLIC"))
                .as("PAINTER's bead is untouched").isEqualByComparingTo("0.025");
    }

    /** A threshold joint IS the painter's bead's geometry, so it takes the shipped silicone figure
     *  rather than a fourth number. */
    @Test
    void aThresholdJointTakesTheShippedSiliconeFigure() {
        for (String position : List.of("приклеювання стиків порожків герметизація",
                "приклеювання стиків в місцях порожків, герметизація силіконом")) {
            assertThat(qty(position, "SEALANT_SILICONE")).as("%s", position)
                    .isEqualByComparingTo("0.025");
        }
    }

    // ---- the bases ------------------------------------------------------------------------------

    /**
     * A damper strip runs the room's OUTLINE, so the position's own m² says nothing about how much
     * is bought. A perimeter is asked once for the whole estimate, which is exactly right for it —
     * and a THICKNESS question with no suggestion would be asked over an empty field, so every
     * depth-driven norm carries one.
     */
    @Test
    void theNonQuantityNormsAskTheRightQuestion() {
        assertThat(norm("монтаж демпферної стрічки", "DAMPER_TAPE").get("basis"))
                .isEqualTo("PERIMETER");

        assertThat(jdbc.queryForList("""
                SELECT n.name_key, n.basis, n.default_param FROM material_norm n
                 WHERE n.owner_id IS NULL AND n.basis = 'THICKNESS' AND n.default_param IS NULL
                """)).isEmpty();
    }

    /**
     * The screed figures and their suggested depths are the ones TILING already ships, reused rather
     * than re-derived — a second suggested depth for the same layer would be a disagreement and not
     * a refinement. The keramzit bed is the layer whose depth actually varies on «Стяжка з
     * керамзитом», so the THICKNESS question asks about it and the 40 mm topping above is a fixed
     * QUANTITY row.
     */
    @Test
    void aScreedReusesTheShippedCoefficientAndDepth() {
        for (String position : List.of("цементно-піщана стяжка до 20м2",
                "цементно-піщана стяжка понад 20м2")) {
            Map<String, Object> norm = norm(position, "SCREED_CEMENT");
            assertThat(norm.get("basis")).as("%s", position).isEqualTo("THICKNESS");
            assertThat((BigDecimal) norm.get("qty_per_unit")).as("%s", position)
                    .isEqualByComparingTo("2.0");
            assertThat((BigDecimal) norm.get("default_param")).as("%s", position)
                    .isEqualByComparingTo("40");
        }

        Map<String, Object> clay = norm("стяжка з керамзитом", "EXPANDED_CLAY");
        assertThat(clay.get("basis")).isEqualTo("THICKNESS");
        assertThat((BigDecimal) clay.get("qty_per_unit")).as("1 mm over 1 m² is 1 l, plus 10 %")
                .isEqualByComparingTo("0.0011");

        Map<String, Object> topping = norm("стяжка з керамзитом", "SCREED_CEMENT");
        assertThat(topping.get("basis")).as("the topping above it is a fixed 40 mm")
                .isEqualTo("QUANTITY");
        assertThat((BigDecimal) topping.get("qty_per_unit")).isEqualByComparingTo("80");

        for (String position : List.of("наливна підлога самовирівнююча",
                "вирівнювання підлоги самовирівнюючим розчином")) {
            Map<String, Object> norm = norm(position, "SELF_LEVELLING");
            assertThat((BigDecimal) norm.get("qty_per_unit")).as("%s", position)
                    .isEqualByComparingTo("1.8");
            assertThat((BigDecimal) norm.get("default_param")).as("%s", position)
                    .isEqualByComparingTo("5");
        }
    }

    // ---- the dictionary -------------------------------------------------------------------------

    /** Every new code exists exactly once, and the ten reusable ones were reused — a second
     *  «Ґрунтівка глибокого проникнення» beside the shipped one would split the shopping list in
     *  two for the same goods. */
    @Test
    void theDictionaryGrewByThirtySixRowsAndDuplicatedNothing() {
        for (String code : NEW_CODES) {
            assertThat(count("SELECT count(*) FROM material WHERE code = ?", code))
                    .as("%s", code).isEqualTo(1);
        }

        for (String code : REUSED_CODES) {
            assertThat(count("SELECT count(*) FROM material WHERE code = ?", code))
                    .as("%s was reused, not re-added", code).isEqualTo(1);
            assertThat(count("""
                    SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                     WHERE n.owner_id IS NULL AND m.code = ?
                       AND (n.trade = 'FLOORING' OR n.trade IS NULL)
                    """, code)).as("%s answers a flooring position", code).isPositive();
        }
    }

    /**
     * None of the new codes carries the {@code PAINT_} prefix, and that is deliberate: the prefix is
     * what the master's {@code PAINT_COVERAGE} habit rescales, and that habit is his answer about
     * HIS WALL PAINT. A parquet lacquer, a deck oil and a 2K polyurethane have coverages of their
     * own, read off their data sheets — V145 settled the same point for enamel and clear varnish.
     */
    @Test
    void noFloorFinishIsRescaledByTheWallPaintHabit() {
        assertThat(NEW_CODES).noneMatch(code -> code.startsWith("PAINT_"));
    }

    /**
     * Packaging follows V127's rule — the smallest size commonly on the shelf, and absent where we
     * cannot pick it, because rounding up to a package the master does not need is the error HE pays
     * for. Every covering is therefore without one: a laminate pack is 1,5 to 2,6 m² depending on
     * the product, so the answer rounds to whole square metres and says nothing about packs.
     */
    @Test
    void aPackageIsEitherKnownOrAbsentAndNeverGuessed() {
        for (String covering : List.of("LAMINATE", "SPC_VINYL", "LVT_VINYL", "FLOOR_ROLL",
                "FLOOR_BOARD", "PARQUET_BLOCK", "DECK_BOARD", "UNDERLAY_LAMINATE", "UNDERLAY_SPC",
                "PE_FILM", "MESH_SCREED", "EXPANDED_CLAY", "DAMPER_TAPE")) {
            assertThat(packageSize(covering)).as("%s", covering).isNull();
        }

        assertThat(packageSize("PARQUET_ADHESIVE")).isEqualByComparingTo("13");
        assertThat(packageSize("MOUNTING_ADHESIVE")).as("a 310 ml cartridge")
                .isEqualByComparingTo("0.31");
        assertThat(packageSize("EPOXY_COATING")).isEqualByComparingTo("20");
        assertThat(packageSize("SKIRTING_PVC")).as("a skirting plank").isEqualByComparingTo("2.5");

        // V133's rule: the calculator divides by `package_size` and then labels the result with
        // `unit`, so a package measured in anything else would be silently mislabelled.
        assertThat(count("""
                SELECT count(*) FROM material
                 WHERE package_unit IS NOT NULL AND package_unit <> unit
                """)).isZero();
    }

    /** A two-component system is bought as A+B and never as one half — the package NAME is what the
     *  master reads on the shelf, and «20 kg» of an epoxy is not a tin of anything. */
    @Test
    void aTwoComponentKitSaysSo() {
        for (String code : List.of("EPOXY_COATING", "PU_TOPCOAT")) {
            assertThat(jdbc.queryForObject(
                    "SELECT package_name FROM material WHERE code = ?", String.class, code))
                    .as("%s", code).isEqualTo("комплект A+B");
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Map<String, Object> norm(String nameKey, String code) {
        return jdbc.queryForMap("""
                SELECT n.basis, n.qty_per_unit, n.default_param, n.waste_percent, n.trade
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, nameKey, code);
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }

    private BigDecimal packageSize(String code) {
        return jdbc.queryForObject("SELECT package_size FROM material WHERE code = ?",
                BigDecimal.class, code);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? -1 : count;
    }
}
