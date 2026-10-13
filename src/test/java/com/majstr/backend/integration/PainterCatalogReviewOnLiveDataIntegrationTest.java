package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V160 — PAINTER's catalog pass (additions, renames, descriptions), read back off the applied schema.
 * The rename machinery itself is pinned on planted masters by {@link DrywallCatalogReviewOnLiveDataIntegrationTest}.
 */
class PainterCatalogReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    private BigDecimal qty(String nameKey, String code) {
        return jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?""", BigDecimal.class, nameKey, code);
    }

    @Test
    void theNewPositionsBuyWhatTheirSiblingsBuy() {
        assertThat(qty("штукатурка стін цементно-вапняною сумішшю (санвузли, укоси, фасади)", "PLASTER_CEMENT_LIME"))
                .isEqualByComparingTo("1.4");
        assertThat(qty("фарбування радіатора опалення (за секцію)", "ENAMEL_METAL")).isEqualByComparingTo("0.05");
        assertThat(qty("покриття дерев'яних поверхонь лазур'ю або лаком у 2 шари", "VARNISH_CLEAR"))
                .isEqualByComparingTo("0.20");
        assertThat(count("""
                SELECT count(*) FROM material_norm WHERE owner_id IS NULL
                   AND name_key = 'антисептична обробка поверхні від цвілі'""")).isZero();
    }

    @Test
    void theLevellingNowSaysWhatItLevelsWithAndBuysIt() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'PUTTY_START_GYPSUM'
                   AND n.name_key = 'вирівнювання стін стартовою шпаклівкою по площині'""")).isOne();
    }

    @Test
    void brandsAbbreviationsAndPriceWordsLeftTheNames() {
        for (String old : List.of("Нанесення декоративної штукатурки ферозит \"мармурова крихта\" - від",
                "Штукатурні роботи (від)", "Монтаж кутника внутрішнього/мет.",
                "Фарбування дверей прих. монтажу (одна сторона)", "Грунтовка поверхонь перед шпаклівкою фарбуванням")) {
            assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER' AND name = ?", old))
                    .as(old).isZero();
            assertThat(count("""
                    SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                     WHERE t.owner_id IS NULL AND i.name = ?""", old)).as(old).isZero();
        }
        assertThat(count("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V160'")).isEqualTo(13);
    }

    @Test
    void categoriesAndDescriptionsLanded() {
        assertThat(jdbc.queryForObject(
                "SELECT category FROM catalog_templates WHERE trade = 'PAINTER' AND name = 'Монтаж 3Д панелей'",
                String.class)).isEqualTo("Оздоблення");
        assertThat(jdbc.queryForObject(
                "SELECT description FROM catalog_templates WHERE trade = 'PAINTER' AND name = 'Грунтування перед шпаклівкою'",
                String.class)).startsWith("Ґрунтовка глибокого проникнення");
    }
}
