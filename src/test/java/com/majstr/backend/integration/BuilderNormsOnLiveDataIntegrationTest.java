package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V151 — the trade-by-trade review's batch 4: BUILDER's first material norms, read back off the
 * applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once. What it cannot do is stop a
 * later migration from quietly norming what the owner ruled must stay unnormed, re-filing a shared
 * position under one trade again, or putting a suggestion outside the bound the calculator enforces —
 * which is what the assertions here are for.</p>
 */
class BuilderNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

    /** Everything V151 added to the dictionary. */
    private static final List<String> NEW_CODES = List.of(
            "GAS_BLOCK", "MASONRY_GLUE_AAC", "BRICK_COMMON", "BRICK_FACING", "CERAMIC_STONE_2NF",
            "SLAG_BLOCK", "WALL_TIE", "CEMENT", "SAND", "CRUSHED_STONE", "CONCRETE_READY",
            "ADHESIVE_ETICS", "EPS_FACADE", "MINERAL_WOOL_FACADE", "DOWEL_FACADE_STEEL", "CORNER_MESH",
            "STARTER_PROFILE", "BITUMEN_MASTIC", "BITUMEN_PRIMER", "ROLL_BITUMEN", "ROOF_MEMBRANE",
            "BATTEN", "OSB", "ROOF_UNDERLAY", "SHINGLES", "PVC_MEMBRANE", "DRIP_EDGE", "PAVING");

    /** Positions PAINTER or TILING ship too, normed at {@code trade = NULL}. */
    private static final List<String> SHARED_WITH_ANOTHER_TRADE = List.of(
            "армування укосів сітка перетяжка", "монтаж пінопласта клей дюбель",
            "монтаж вати клей дюбель", "утеплення укосів пінопластом", "утеплення укосів ватою",
            "утеплення цоколя пінополістиролом", "монтаж стартової планки",
            "облицювання клінкерною плиткою", "гідроізоляція плівкова");

    /** V148 put these FLOORING-normed positions into BUILDER's screed bundle; V151 re-files them. */
    private static final List<String> REFILED_FROM_FLOORING = List.of(
            "монтаж демпферної стрічки", "монтаж утеплювача під стяжку", "плівкова ізоляція під стяжку");

    @Autowired JdbcTemplate jdbc;

    // ---- coverage -------------------------------------------------------------------------------

    /** 106 of the 229 positions answer; the rest are piece goods, layouts, rebar and rollups. */
    @Test
    void oneHundredAndSixBuilderPositionsAnswer() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates t
                 WHERE t.trade = 'BUILDER' AND t.type = 'WORK'
                   AND EXISTS (SELECT 1 FROM material_norm n
                                WHERE n.owner_id IS NULL
                                  AND (n.trade = 'BUILDER' OR n.trade IS NULL)
                                  AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")"))
                .isEqualTo(103); // V162: +2 normed additions and the small screed; V167: −6 duplicate wordings
    }

    /**
     * The owner's rulings 3-5 (2026-10-13): a name that does not say which block, two materials in
     * one name, and a «комплекс» rollup a master could put beside its own parts stay UNNORMED — the
     * calculator honestly says «not covered» instead of guessing.
     */
    @Test
    void whatTheOwnerRuledUnnormedStaysUnnormed() {
        for (String position : List.of(
                "кладка стіни з керамоблока 38х60х20",
                "армування кладки (сіткою, арматурою)",
                "армування кладки сіткою арматурою",
                "утеплення фасада комплекс пінопласт сітка декор штукатурка",
                "утеплення фасада комплекс вата сітка декор штукатурка",
                "монтаж двоскатного даху комплекс",
                "навіс легкий комплекс",
                "будівництво гаража комплекс")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?",
                    position)).as("%s", position).isZero();
        }
    }

    /** The mortar step and earthworks record «consumes nothing» rather than reading as a gap. The
     *  mortar one matters most: «Коробка будинку…» carries it beside the masonry, which already buys
     *  the cement and sand. */
    @Test
    void theMortarStepAndEarthworksRecordAVerdict() {
        List<String> verdicts = jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'BUILDER'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """, String.class);

        assertThat(verdicts).hasSize(14).contains( // V162: the renamed site levelling, under both names
                "приготування розчину для кладки без щебня",
                "копання траншей ям котловану вручну",
                "зворотня засипка траншеї");
    }

    // ---- figures --------------------------------------------------------------------------------

    /** Ruling 1: masonry mortar is cement + sand, M100 (РЕКН-6 6-47-18), 0,24 m³ per m³ of brick. */
    @Test
    void brickworkBuysBricksCementAndSand() {
        assertThat(qty("кладка цегли 380 мм", "BRICK_COMMON")).isEqualByComparingTo("394");
        assertThat(qty("кладка цегли 380 мм", "CEMENT")).isEqualByComparingTo("73");
        assertThat(qty("кладка цегли 380 мм", "SAND")).isEqualByComparingTo("0.29");
        assertThat(qty("кладка стін з газоблоку або піноблоку 300 мм", "GAS_BLOCK"))
                .isEqualByComparingTo("0.99");
        assertThat(qty("кладка стін з газоблоку або піноблоку 300 мм", "MASONRY_GLUE_AAC"))
                .isEqualByComparingTo("25");
    }

    /** The ETICS base coat was bought by nobody — not the builder, not the painter. It rides the
     *  shared mesh position, trade-less, beside the mesh that was already there. */
    @Test
    void theFacadeMeshNowBuysItsBaseCoat() {
        Map<String, Object> glue = norm("армування фасаду сітка перетяжка", "ADHESIVE_ETICS");
        assertThat(glue.get("trade")).isNull();
        assertThat((BigDecimal) glue.get("qty_per_unit")).isEqualByComparingTo("5.0");
        assertThat(qty("армування фасаду сітка перетяжка", "MESH_FIBERGLASS"))
                .isEqualByComparingTo("1.1");
    }

    /** The corner bead is bought on its own position only: the facade bundle carries it beside the
     *  reveal mesh, and a bead on both would buy it twice. */
    @Test
    void theCornerBeadIsBoughtOnce() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'CORNER_MESH'
                """)).isEqualTo(1);
    }

    /** Clinker grout keeps its own 10 mm joint, so the master's tile-joint habit does not rescale
     *  a wide-joint figure (review B-113). */
    @Test
    void clinkerGroutIsWrittenForItsOwnJoint() {
        for (String position : List.of("облицювання фасаду клінкерною плиткою",
                "облицювання клінкерною плиткою")) {
            Map<String, Object> grout = norm(position, "TILE_GROUT");
            assertThat((BigDecimal) grout.get("qty_per_unit")).as("%s", position)
                    .isEqualByComparingTo("2.9");
            assertThat((BigDecimal) grout.get("baseline_param")).as("%s", position)
                    .isEqualByComparingTo("10");
        }
    }

    // ---- bases and bounds -----------------------------------------------------------------------

    /**
     * A suggestion outside the calculator's per-question bound is refused the moment it is
     * pre-filled, so the position would ask forever. The sand cushion is why this exists: the
     * review's 200 mm is above the 150 mm THICKNESS bound.
     */
    @Test
    void everySuggestionFitsTheBoundItIsCheckedAgainst() {
        assertThat(jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL
                   AND ((basis = 'THICKNESS' AND (default_param IS NULL OR default_param > 150))
                     OR (basis = 'SECTION' AND default_param > 5))
                """, String.class)).isEmpty();

        assertThat((BigDecimal) norm("піщана подушка з ущільненням", "SAND").get("default_param"))
                .isEqualByComparingTo("150");
    }

    /** A partition is priced by the m², so its thickness is asked; a wall by the m³ is not. */
    @Test
    void aPartitionAsksItsThicknessAndAWallDoesNot() {
        Map<String, Object> partition = norm("кладка перегородки з піноблоку газоблоку до 50м2", "GAS_BLOCK");
        assertThat(partition.get("basis")).isEqualTo("THICKNESS");
        assertThat((BigDecimal) partition.get("default_param")).isEqualByComparingTo("100");

        assertThat(norm("кладка стін з газоблоку або піноблоку 200 мм", "GAS_BLOCK").get("basis"))
                .isEqualTo("QUANTITY");
        assertThat(norm("гідроізоляція перед першим рядом", "ROLL_BITUMEN").get("basis"))
                .isEqualTo("SECTION");
    }

    /** Batch 2's ruling: a shipped norm's own allowance would override the master's habit. */
    @Test
    void noBuilderNormOverridesTheMastersCuttingAllowance() {
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'BUILDER' AND waste_percent <> 0
                """)).isZero();
    }

    // ---- shared positions ----------------------------------------------------------------------

    /** A position two trades ship is normed once, trade-less, and that is a fact about the library. */
    @Test
    void sharedPositionsAreNormedWithoutATrade() {
        for (String position : SHARED_WITH_ANOTHER_TRADE) {
            assertThat(count("""
                    SELECT count(*) FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND trade IS NOT NULL
                    """, position)).as("%s is filed under a trade", position).isZero();
            assertThat(jdbc.queryForList("SELECT DISTINCT t.trade FROM catalog_templates t"
                    + " WHERE t.type = 'WORK' AND " + NAME_KEY + " = ?", String.class, position))
                    .as("%s", position).contains("BUILDER").hasSizeGreaterThan(1);
        }
    }

    /** The three screed norms reach a builder's line now, and they were moved, not copied. */
    @Test
    void theScreedNormsBuilderSharesWithFlooringAreTradeless() {
        for (String position : REFILED_FROM_FLOORING) {
            assertThat(count("""
                    SELECT count(*) FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND trade IS NULL
                    """, position)).as("%s", position).isEqualTo(1);
            assertThat(count("""
                    SELECT count(*) FROM material_norm
                     WHERE owner_id IS NULL AND name_key = ? AND trade IS NOT NULL
                    """, position)).as("%s", position).isZero();
        }
    }

    // ---- dictionary -----------------------------------------------------------------------------

    @Test
    void theDictionaryGrewByTwentyEightRows() {
        for (String code : NEW_CODES) {
            assertThat(count("SELECT count(*) FROM material WHERE code = ?", code))
                    .as("%s", code).isEqualTo(1);
            assertThat(count("""
                    SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                     WHERE n.owner_id IS NULL AND m.code = ?
                    """, code)).as("%s answers a position", code).isPositive();
        }
        assertThat(NEW_CODES).noneMatch(code -> code.startsWith("PAINT_"));
    }

    /** V127's rule: a package only where the shelf agrees on one. */
    @Test
    void aPackageIsEitherKnownOrAbsent() {
        assertThat(packageSize("CEMENT")).isEqualByComparingTo("25");
        assertThat(packageSize("ADHESIVE_ETICS")).isEqualByComparingTo("25");
        assertThat(packageSize("ROOF_MEMBRANE")).isEqualByComparingTo("75");
        assertThat(packageSize("MESH_FIBERGLASS")).as("a 1,1 × 50 m roll").isEqualByComparingTo("55");
        for (String code : List.of("SAND", "CRUSHED_STONE", "CONCRETE_READY", "ROLL_BITUMEN", "SHINGLES")) {
            assertThat(packageSize(code)).as("%s", code).isNull();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Map<String, Object> norm(String nameKey, String code) {
        return jdbc.queryForMap("""
                SELECT n.basis, n.qty_per_unit, n.default_param, n.baseline_param, n.trade
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, nameKey, code);
    }

    private BigDecimal qty(String nameKey, String code) {
        return (BigDecimal) norm(nameKey, code).get("qty_per_unit");
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
