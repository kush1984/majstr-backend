package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V155 — the trade-by-trade review's batch 8: METAL's first material norms, read back off the
 * applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once; these assertions keep a
 * later migration from norming the steel of a fabricated piece, or letting the wall-paint habit
 * rescale metal enamel.</p>
 */
class MetalNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

    private static final List<String> NEW_CODES = List.of(
            "PRIMER_METAL", "ENAMEL_METAL", "PROFNASTYL", "SCREW_ROOFING", "POLYCARBONATE_CELL",
            "THERMO_WASHER", "PROFILE_PC_CONNECT", "PANEL_3D", "POST_3D", "CLAMP_3D",
            "CONCRETE_DRY_M300", "ANCHOR_BASEPLATE", "SLIDING_GATE_KIT", "POST_CAP");

    @Autowired JdbcTemplate jdbc;

    @Test
    void nineteenMetalPositionsAnswer() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates t
                 WHERE t.trade = 'METAL' AND t.type = 'WORK'
                   AND EXISTS (SELECT 1 FROM material_norm n
                                WHERE n.owner_id IS NULL
                                  AND (n.trade = 'METAL' OR n.trade IS NULL)
                                  AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")"))
                .isEqualTo(19);
    }

    /** Ruling 2: two coats of enamel, under a code the PAINT_ habit prefix does not reach. */
    @Test
    void primerAndEnamelPerSquareMetreOfSteel() {
        assertThat(qty("ґрунтування металу", "PRIMER_METAL")).isEqualByComparingTo("0.10");
        assertThat(qty("фарбування металу", "ENAMEL_METAL")).isEqualByComparingTo("0.20");
        assertThat(NEW_CODES).noneMatch(code -> code.startsWith("PAINT_"));
    }

    @Test
    void claddingBuysSheetAndFixings() {
        assertThat(qty("монтаж профнастилу на паркан", "PROFNASTYL")).isEqualByComparingTo("1.05");
        assertThat(qty("монтаж профнастилу на паркан", "SCREW_ROOFING")).isEqualByComparingTo("6");
        assertThat(qty("монтаж покрівлі навісу з профнастилу", "SCREW_ROOFING")).isEqualByComparingTo("8");
        assertThat(qty("монтаж покрівлі навісу з полікарбонату", "THERMO_WASHER")).isEqualByComparingTo("4");
        assertThat(qty("монтаж секції 3d-сітки", "PANEL_3D")).isEqualByComparingTo("0.4");
    }

    /** Ruling 1: a fixed figure per post — THICKNESS stops at 150 mm and a post hole does not. */
    @Test
    void postsAreConcretedPerPost() {
        assertThat(qty("монтаж стовпів для паркану", "CONCRETE_DRY_M300")).isEqualByComparingTo("55");
        assertThat(qty("бетонування стовпів під ворота", "CONCRETE_DRY_M300")).isEqualByComparingTo("140");
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'CONCRETE_DRY_M300' AND n.basis <> 'QUANTITY'
                """)).isZero();
        assertThat(qty("анкерування опорних пластин", "ANCHOR_BASEPLATE")).isEqualByComparingTo("4");
    }

    /** Ruling 4: the steel of a fabricated piece is its drawing, and welding hours have no source. */
    @Test
    void fabricationWeldingAndHangingAreNotNormed() {
        for (String position : List.of(
                "виготовлення відкатних воріт (каркас, зашивка)",
                "виготовлення металоконструкцій",
                "виготовлення каркасу ангара",
                "зварювальні роботи (напівавтомат)",
                "зварювання каркасу та лаг паркану",
                "монтаж металоконструкцій",
                "монтаж опорних стовпів навісу",
                "бетонування закладної під відкатні ворота",
                "монтаж автоматики для воріт",
                "піскоструминна обробка")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?",
                    position)).as("%s", position).isZero();
        }
    }

    @Test
    void servicesAndCuttingRecordAVerdict() {
        assertThat(jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'METAL'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """, String.class)).hasSize(9) // V166: cutting and bending under both names
                .contains("гаряче цинкування (послуга)", "різка металу", "демонтаж старого паркану");
    }

    @Test
    void theDictionaryGrewByFourteenRowsWithZeroWaste() {
        assertThat(count("SELECT count(*) FROM material WHERE code IN ("
                + String.join(",", NEW_CODES.stream().map(c -> "'" + c + "'").toList()) + ")"))
                .isEqualTo(14);
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'METAL' AND waste_percent <> 0
                """)).isZero();
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? -1 : count;
    }
}
