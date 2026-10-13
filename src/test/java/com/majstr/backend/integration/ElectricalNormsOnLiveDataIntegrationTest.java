package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V153 — the trade-by-trade review's batch 6: ELECTRICAL's first material norms, read back off the
 * applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once; these assertions keep a
 * later migration from norming what the client chooses, putting clips into a chase, or merging two
 * panel sizes into one material.</p>
 */
class ElectricalNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

    @Autowired JdbcTemplate jdbc;

    @Test
    void ninetyTwoElectricalPositionsAnswer() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates t
                 WHERE t.trade = 'ELECTRICAL' AND t.type = 'WORK'
                   AND EXISTS (SELECT 1 FROM material_norm n
                                WHERE n.owner_id IS NULL
                                  AND (n.trade = 'ELECTRICAL' OR n.trade IS NULL)
                                  AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")"))
                .isEqualTo(92);
    }

    /** Ruling 1: a metre of laying is a metre of cable, and of conduit where the name says so. */
    @Test
    void layingBuysCableAndItsDuctByTheMetre() {
        assertThat(qty("прокладка кабелю", "CABLE_COPPER")).isEqualByComparingTo("1");
        assertThat(qty("прокладка кабелю в гофрі", "CABLE_COPPER")).isEqualByComparingTo("1");
        assertThat(qty("прокладка кабелю в гофрі", "CONDUIT_16")).isEqualByComparingTo("1");
        assertThat(qty("прокладка силового кабелю в гофрі трубі", "CABLE_POWER")).isEqualByComparingTo("1");
        assertThat(qty("прокладка силового кабелю в гофрі трубі", "CONDUIT_20")).isEqualByComparingTo("1");
        assertThat(qty("монтаж кабель каналу", "DOWEL_NAIL")).isEqualByComparingTo("3");
    }

    /** Ruling 2 and the boxes: gypsum is a calculation, shown, and forkable. */
    @Test
    void boxesAndChasesBuyGypsum() {
        assertThat(qty("монтаж підрозетника", "BOX_FLUSH")).isEqualByComparingTo("1");
        assertThat(qty("монтаж підрозетника", "GYPSUM_BUILDING")).isEqualByComparingTo("0.15");
        assertThat(qty("монтаж розподільчої коробки в стіну", "GYPSUM_BUILDING")).isEqualByComparingTo("0.3");
        assertThat(qty("заробка штроб", "GYPSUM_BUILDING")).isEqualByComparingTo("0.4");
    }

    /** Ruling 3: a breaker is a breaker; the two spellings of one device buy the same material. */
    @Test
    void modularDevicesAreOnePerPosition() {
        assertThat(qty("установка автомата однополюсного", "MCB_1P")).isEqualByComparingTo("1");
        assertThat(qty("установка пзв двополюсний", "RCD_2P")).isEqualByComparingTo("1");
        assertThat(qty("установка пристрою захисного відключення (пзв) - двухполюсний", "RCD_2P"))
                .isEqualByComparingTo("1");
    }

    /** One panel size per material, so a shopping list never reads «корпус ×2» for two boxes. */
    @Test
    void everyPanelSizeIsItsOwnMaterial() {
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key LIKE 'монтаж електрощита%'
                   AND m.code LIKE 'PANEL_BOX%'
                """, String.class)).hasSize(5);
    }

    /** What the client chooses, and what the circuit decides, stays unnormed. */
    @Test
    void mechanismsLightsEquipmentAndCircuitDetailsAreNotNormed() {
        for (String position : List.of(
                "установка та підключення розеток та вимикачів",
                "установка та підключення точкового світильника софіта",
                "установка стабілізатора напруги",
                "монтаж зарядної станції для електромобіля",
                "укладка підлоги електричної до 10м2",
                "фіксація нагрівального мата спеціальним клеєм",
                "збірка розподільчої коробки розпаювання проводів",
                "установка модульного імпульсного реле, таймера або фотореле")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?",
                    position)).as("%s", position).isZero();
        }
    }

    @Test
    void chasingDrillingAndTestsRecordAVerdict() {
        assertThat(jdbc.queryForList("""
                SELECT name_key FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'ELECTRICAL'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """, String.class)).hasSize(41)
                .contains("штроблення під електрокабель 20х20 мм (в бетоні)",
                        "свердління наскрізних отворів у бетоні",
                        "вимірювання опору ізоляції кабельних ліній");
    }

    @Test
    void theDictionaryGrewByThirtyNineRowsWithZeroWaste() {
        assertThat(count("""
                SELECT count(*) FROM material
                 WHERE code IN ('GYPSUM_BUILDING','BOX_FLUSH','BOX_JUNCTION_FLUSH','BOX_JUNCTION_SURFACE',
                                'CABLE_COPPER','CABLE_POWER','CABLE_UTP','CABLE_COAX','CONDUIT_16',
                                'CONDUIT_20','METAL_HOSE','PIPE_HDPE_CABLE','TRUNKING','CABLE_LUG',
                                'PANEL_BOX_36','PANEL_BOX_60','PANEL_BOX_90','PANEL_BOX_180',
                                'PANEL_BOX_FLUSH','MCB_1P','MCB_2P','MCB_3P','MCB_4P','RCBO','RCD_2P',
                                'RCD_4P','SPD','AFDD','VOLTAGE_RELAY','VOLTAGE_RELAY_3PH',
                                'CONTACTOR_220','CONTACTOR_380','CHANGEOVER_2P','CHANGEOVER_4P',
                                'CHANGEOVER_RELAY_4P','REVERSING_SWITCH','NEUTRAL_BAR',
                                'DISTRIBUTION_BLOCK','CROSS_MODULE')
                """)).isEqualTo(39);
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'ELECTRICAL' AND waste_percent <> 0
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
