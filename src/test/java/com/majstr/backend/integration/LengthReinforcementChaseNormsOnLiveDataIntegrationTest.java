package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V168 — the LENGTH, REINFORCEMENT and CHASE_SECTION norms, read back off the applied schema.
 */
class LengthReinforcementChaseNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void aRiserBuysItsPipeByTheLengthWithAStoreySuggested() {
        Map<String, Object> norm = jdbc.queryForMap("""
                SELECT n.qty_per_unit, n.default_param, n.basis FROM material_norm n
                  JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'PIPE_SEWER_110'
                   AND n.name_key = 'монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель'
                """);
        assertThat(norm.get("basis")).isEqualTo("LENGTH");
        assertThat((BigDecimal) norm.get("qty_per_unit")).isEqualByComparingTo("1");
        assertThat((BigDecimal) norm.get("default_param")).isEqualByComparingTo("3.0");
    }

    /** The design sets the ratio: no REINFORCEMENT norm carries a suggestion. */
    @Test
    void reinforcedConcreteAsksForItsRatioWithoutGuessing() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'REBAR' AND n.basis = 'REINFORCEMENT'
                   AND n.default_param IS NULL""", Integer.class)).isEqualTo(7);
    }

    @Test
    void theChaseFillingIsPerSquareCentimetreUnderBothNames() {
        assertThat(jdbc.queryForList("""
                SELECT n.basis || ' ' || n.qty_per_unit || ' ' || n.default_param FROM material_norm n
                  JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'GYPSUM_BUILDING'
                   AND n.name_key IN ('заробка штроб', 'закладення штроб гіпсовою сумішшю')
                """, String.class)).hasSize(2).allSatisfy(row -> assertThat(row).startsWith("CHASE_SECTION 0.1"));
    }
}
