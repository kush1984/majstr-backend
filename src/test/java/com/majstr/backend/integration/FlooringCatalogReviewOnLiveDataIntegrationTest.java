package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V161 — FLOORING's catalog pass, read back off the applied schema. FLOORING stays fully answered;
 * the rename machinery itself is pinned by {@link DrywallCatalogReviewOnLiveDataIntegrationTest}.
 */
class FlooringCatalogReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String NAME_KEY = """
            lower(btrim(replace(replace(regexp_replace(t.name, '\\s+', ' ', 'g'), '( ', '('), ' )', ')')))""";

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

    /** The two V147 named as needing the owner's word are the only ones that answer nothing. */
    @Test
    void everyFlooringPositionButTheTwoKnownOnesAnswers() {
        assertThat(jdbc.queryForList("""
                SELECT t.name FROM catalog_templates t
                 WHERE t.trade = 'FLOORING' AND t.type = 'WORK'
                   AND t.added_in_version <> 26 -- V176's additions shipped priced, not yet normed
                   AND NOT EXISTS (SELECT 1 FROM material_norm n
                                    WHERE n.owner_id IS NULL AND (n.trade = 'FLOORING' OR n.trade IS NULL)
                                      AND n.unit = t.unit AND n.name_key = """ + NAME_KEY + ")", String.class))
                .containsExactlyInAnyOrder("Машинна стяжка самовирівнююча",
                        "Монтаж та виготовлення ніші під плінтус прихованого монтажу");
    }

    @Test
    void theNewPositionsBuyWhatTheyLay() {
        assertThat(qty("укладання ламінату ялинкою", "LAMINATE")).isEqualByComparingTo("1.12");
        assertThat(qty("покриття паркету олією (олією з воском), 1 шар", "PARQUET_OIL")).isEqualByComparingTo("0.04");
        assertThat(qty("укладання фанери або osb на стяжку чи лаги", "OSB")).isEqualByComparingTo("1.05");
    }

    /** One row for a master with both trades, and a norm both trades reach. */
    @Test
    void theDryFloorIsOneSharedPosition() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE name = 'Монтаж сухої збірної підлоги з гіпсоволокна'"))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT coalesce(trade, 'any') FROM material_norm
                 WHERE owner_id IS NULL AND name_key = 'монтаж сухої збірної підлоги з гіпсоволокна'""", String.class))
                .containsExactly("any");
    }

    @Test
    void theFloorPrimingLeftItsOutlierPriceAndItsOldName() {
        assertThat(jdbc.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                 WHERE trade = 'FLOORING' AND name = 'Ґрунтування основи підлоги'""", BigDecimal.class))
                .isEqualByComparingTo("35");
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE name = 'Грунтовка підлоги підготовчі роботи'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V161'")).isEqualTo(11);
    }

    @Test
    void theTerraceHasOneCategory() {
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT category FROM catalog_templates
                 WHERE trade = 'FLOORING' AND name IN ('Монтаж терасної дошки', 'Фарбування терасної дошки', 'Укладання геотекстилю')
                """, String.class)).containsExactly("Тераса");
    }
}
