package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V154 — the trade-by-trade review's batch 7: DEMOLITION's material norms, read back off the
 * applied schema.
 *
 * <p>The migration's own {@code DO $$} block proves the set landed once; these assertions keep a
 * later migration from putting bags on the demolition positions (they are bought on «Фасування»),
 * or norming a fixture removal or an opening whose consumption the name does not carry.</p>
 */
class DemolitionNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

    @Autowired JdbcTemplate jdbc;

    @Test
    void fortyFourDemolitionPositionsAnswer() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates t
                 WHERE t.trade = 'DEMOLITION' AND t.type = 'WORK'
                   AND EXISTS (SELECT 1 FROM material_norm n
                                WHERE n.owner_id IS NULL
                                  AND (n.trade = 'DEMOLITION' OR n.trade IS NULL)
                                  AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")"))
                .isEqualTo(53); // V165: + 9 normed additions
    }

    /** Ruling 2: a 0,9×2,1 doorway with a 30 cm overlap on three sides. */
    @Test
    void theDustCurtainBuysFilmAndTape() {
        assertThat(qty("пилозахисна завіса з плівки на дверний проріз", "PE_FILM")).isEqualByComparingTo("4");
        assertThat(qty("пилозахисна завіса з плівки на дверний проріз", "TAPE_MASKING")).isEqualByComparingTo("7");
    }

    /** DEMOLITION ships the door protection too, so its norm answers for every trade. */
    @Test
    void theEntranceDoorProtectionIsTradeLess() {
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT coalesce(trade, 'any') FROM material_norm
                 WHERE owner_id IS NULL AND name_key = 'захист вхідних дверей картоном'
                """, String.class)).containsExactly("any");
    }

    /** Ruling 1: bags are bought once, on «Фасування сміття в мішки», never per m² of demolition. */
    @Test
    void bagsAreBoughtOnlyWhereTheyAreFilled() {
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT n.name_key FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'DEBRIS_BAG'
                """, String.class)).containsExactly("фасування сміття в мішки");
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND name_key IN ('збивання штукатурки зі стін', 'демонтаж стяжки бетонної')
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """)).isEqualTo(2);
    }

    /** Ruling 3: the pipe ends and the lintel depend on what the name does not say. */
    @Test
    void fixtureRemovalsAndOpeningsAreNotNormed() {
        for (String position : List.of(
                "демонтаж унітазу", "демонтаж умивальника раковини", "демонтаж ванни",
                "демонтаж душової кабіни", "демонтаж змішувача", "демонтаж радіатора опалення",
                "демонтаж рушникосушки", "демонтаж бойлера",
                "вирізка прорізу в цегляній стіні", "розширення дверного прорізу",
                "розширення отвору під вхідні двері")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?",
                    position)).as("%s", position).isZero();
        }
    }

    @Test
    void verdictsOnSharedPositionsAreTradeLess() {
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'DEMOLITION'
                   AND material_id IS NULL AND qty_per_unit IS NULL
                """)).isEqualTo(46); // V165: + 8 new verdicts, + 7 renamed keys
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade IS NULL AND material_id IS NULL
                   AND name_key IN ('демонтаж старої електропроводки', 'демонтаж старих труб',
                                    'змивання побілки зі стелі', 'зняття шпалер')
                """)).isEqualTo(4);
        assertThat(count("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NULL AND trade = 'DEMOLITION' AND waste_percent <> 0
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
