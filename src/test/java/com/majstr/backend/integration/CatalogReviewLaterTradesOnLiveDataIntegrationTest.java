package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V163-V166 — the catalog pass for ELECTRICAL, PLUMBING, DEMOLITION and METAL, read back off the
 * applied schema. The rename machinery itself is pinned on planted masters by
 * {@link DrywallCatalogReviewOnLiveDataIntegrationTest}.
 */
class CatalogReviewLaterTradesOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    private BigDecimal price(String trade, String name) {
        return jdbc.queryForObject("SELECT suggested_price FROM catalog_templates WHERE trade = ? AND name = ?",
                BigDecimal.class, trade, name);
    }

    @Test
    void everyPassRecordedItsRenames() {
        for (Map.Entry<String, Integer> e : Map.of("V163", 15, "V164", 18, "V165", 7, "V166", 5).entrySet()) {
            assertThat(count("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = ?", e.getKey()))
                    .as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    /** Placeholders gone: a 180-module panel is no longer cheaper than a 12-module one. */
    @Test
    void electricalPanelsAndBreakersArePricedInOrder() {
        assertThat(price("ELECTRICAL", "Монтаж електрощита накладного 120-180 модулів")).isEqualByComparingTo("2500");
        assertThat(price("ELECTRICAL", "Установка автомата чотириполюсного"))
                .isGreaterThan(price("ELECTRICAL", "Установка автомата двополюсного"));
        assertThat(price("ELECTRICAL", "Монтаж ТВ-кабелю")).isEqualByComparingTo("60");
    }

    /** The chase filling keeps its gypsum under the new name, and under the old one. */
    @Test
    void theChaseFillingBuysGypsumUnderBothNames() {
        for (String key : List.of("заробка штроб", "закладення штроб гіпсовою сумішшю")) {
            assertThat(count("""
                    SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                     WHERE n.owner_id IS NULL AND m.code = 'GYPSUM_BUILDING' AND n.name_key = ?""", key))
                    .as(key).isOne();
        }
    }

    @Test
    void plumbingBrandsAndLettersLeftTheNames() {
        for (String old : List.of("Монтаж душової системи прихованого типу Smart Box", "Монтаж зворотного клапана каналізації Ф50",
                "Установка та підключення рушникоосушки", "Установка простого унітазу біде")) {
            assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PLUMBING' AND name = ?", old))
                    .as(old).isZero();
        }
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'SEWER_CHECK_VALVE_50'
                   AND n.name_key = 'монтаж зворотного клапана каналізації ø50'""")).isOne();
    }

    /** V154's rule: taking things apart consumes nothing; the film cover buys film for both trades. */
    @Test
    void demolitionAdditionsRecordAVerdictAndTheFilmCoverBuysFilm() {
        assertThat(count("""
                SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND material_id IS NULL
                   AND name_key IN ('збивання штукатурки зі стелі', 'демонтаж цегляної кладки', 'демонтаж бетону відбійним молотком')
                """)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT coalesce(trade, 'any') FROM material_norm
                 WHERE owner_id IS NULL AND name_key = 'укриття плівкою підлоги, дверей, сантехніки'""", String.class))
                .containsExactly("any");
    }

    @Test
    void theMetalStairFrameSaysItIsPerStep() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'METAL' AND name = 'Виготовлення металевого каркасу сходів (за сходинку)'"))
                .isOne();
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND i.name = 'Зварювання каркасу та лаг паркану'""")).isZero();
    }
}
