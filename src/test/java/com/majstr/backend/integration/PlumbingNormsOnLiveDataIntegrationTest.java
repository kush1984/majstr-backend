package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V152 — the trade-by-trade review's batch 5: PLUMBING's first material norms and the STEP basis,
 * read back off the applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once; these assertions keep a
 * later migration from norming an appliance the owner ruled out, turning the loop-spacing norm back
 * into a fixed figure, or shipping a spacing suggestion the calculator would refuse.</p>
 */
class PlumbingNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

    private static final String UFH = "монтаж трубної системи тепла підлога з утеплювачем";

    private static final List<String> NEW_CODES = List.of(
            "PIPE_WATER", "PIPE_INSULATION", "PIPE_CLIP", "WALL_PLATE_ELBOW", "PIPE_SEWER",
            "SEWER_CLAMP", "SEWER_CLAMP_110", "SEWER_TEE_110", "SEWER_COMPENSATOR", "SEWER_CAST_ADAPTER",
            "SEWER_REVISION", "SEWER_CHECK_VALVE_50", "SEWER_CHECK_VALVE_110", "INSTALL_FRAME_WC",
            "FLUSH_PLATE", "THERMO_HEAD", "RADIATOR_LEGS", "UFH_PIPE", "UFH_CLIP", "UFH_INSULATION");

    @Autowired JdbcTemplate jdbc;

    /** 60 since V152; V154's trade-less verdict on «Демонтаж старих труб» answers here too. */
    @Test
    void sixtyOnePlumbingPositionsAnswer() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates t
                 WHERE t.trade = 'PLUMBING' AND t.type = 'WORK'
                   AND EXISTS (SELECT 1 FROM material_norm n
                                WHERE n.owner_id IS NULL
                                  AND (n.trade = 'PLUMBING' OR n.trade IS NULL)
                                  AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")"))
                .isEqualTo(61);
    }

    /** Ruling 3: a mounting kit is normed, the appliance the client chooses is not. */
    @Test
    void appliancesAreNotNormedButTheirMountingKitsAre() {
        for (String appliance : List.of("установка ванни простої", "установка змішувача простого",
                "установка унітазу на готову інсталяцію", "установка радіатора опалення простого",
                "установка настінного двоконтурного котла", "монтаж септика")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?",
                    appliance)).as("%s", appliance).isZero();
        }
        assertThat(qty("монтаж інсталяції для унітазу", "INSTALL_FRAME_WC")).isEqualByComparingTo("1");
        assertThat(qty("установка терморегулятора на радіатор", "THERMO_HEAD")).isEqualByComparingTo("1");
    }

    /** Ruling 1: the usual flat's diameter, the arithmetic on screen, a fork to correct it. */
    @Test
    void clipsAssumeTheUsualFlatsPipe() {
        assertThat(qty("прокладання труб на хомутах", "PIPE_CLIP")).isEqualByComparingTo("1.7");
        assertThat(qty("прокладання труб на хомутах (опалення)", "PIPE_CLIP")).isEqualByComparingTo("1.8");
        assertThat(qty("прокладання труб на хомутах (каналізація)", "SEWER_CLAMP")).isEqualByComparingTo("2.0");
    }

    /**
     * Ruling 2: the underfloor pipe and clips DIVIDE by the loop spacing, written at a 1 mm spacing
     * so the suggested 150 mm gives Purmo's own 6,5 m and 14 clips per m².
     */
    @Test
    void underfloorPipeIsAskedForItsSpacing() {
        for (Map.Entry<String, String> e : Map.of("UFH_PIPE", "6.5", "UFH_CLIP", "14").entrySet()) {
            Map<String, Object> norm = norm(UFH, e.getKey());
            assertThat(norm.get("basis")).as(e.getKey()).isEqualTo("STEP");
            BigDecimal suggested = (BigDecimal) norm.get("default_param");
            assertThat(suggested).isEqualByComparingTo("150");
            assertThat(((BigDecimal) norm.get("qty_per_unit")).divide(suggested))
                    .as("%s at 150 mm", e.getKey()).isEqualByComparingTo(e.getValue());
        }
        assertThat(norm(UFH, "DAMPER_TAPE").get("basis")).isEqualTo("PERIMETER");
    }

    /** A spacing suggestion above the calculator's 500 mm bound would be refused when pre-filled. */
    @Test
    void everyStepNormSuggestsASpacingInsideTheBound() {
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND basis = 'STEP'
                   AND (default_param IS NULL OR default_param <= 0 OR default_param > 500)
                """)).isZero();
    }

    /** The compensator is bought on its own position, not also on the riser beside it. */
    @Test
    void aRiserBuysItsTeeAndClampsButNotTheCompensator() {
        String riser = "монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель";
        assertThat(qty(riser, "SEWER_TEE_110")).isEqualByComparingTo("1");
        assertThat(qty(riser, "SEWER_CLAMP_110")).isEqualByComparingTo("2");
        assertThat(qty(riser, "SEWER_CAST_ADAPTER")).isEqualByComparingTo("2");
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'SEWER_COMPENSATOR'
                """)).isEqualTo(1);
    }

    @Test
    void testsStartupAndChasingRecordAVerdict() {
        assertThat(jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'PLUMBING'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """, String.class)).hasSize(26)
                .contains("перевірка системи водопроводу тиском", "штроблення в бетоні (вода)",
                        "запуск системи опалення");
    }

    @Test
    void theDictionaryGrewWithoutDuplicates() {
        for (String code : NEW_CODES) {
            assertThat(count("SELECT count(*) FROM material WHERE code = ?", code))
                    .as("%s", code).isEqualTo(1);
        }
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'PLUMBING' AND waste_percent <> 0
                """)).isZero();
    }

    private Map<String, Object> norm(String nameKey, String code) {
        return jdbc.queryForMap("""
                SELECT n.basis, n.qty_per_unit, n.default_param
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, nameKey, code);
    }

    private BigDecimal qty(String nameKey, String code) {
        return (BigDecimal) norm(nameKey, code).get("qty_per_unit");
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? -1 : count;
    }
}
