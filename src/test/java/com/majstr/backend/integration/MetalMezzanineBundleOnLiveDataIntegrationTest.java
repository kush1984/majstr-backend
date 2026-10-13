package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V156 — METAL's «Антресоль / майданчик» bundle, read back off the applied schema: a job sequence
 * with the finish BEFORE erection, every line a live catalog position (a miss prices at 0 ₴).
 */
class MetalMezzanineBundleOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String BUNDLE = """
            FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
             WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'METAL'
               AND t.name = 'Антресоль / майданчик'
            """;

    @Autowired JdbcTemplate jdbc;

    @Test
    void primerAndPaintComeBeforeErection() {
        assertThat(jdbc.queryForList("SELECT i.name " + BUNDLE + " ORDER BY i.sort_order", String.class))
                .containsExactly(
                        "Виготовлення закладних деталей",
                        "Виготовлення антресолі та площадки",
                        "Зачистка та знежирення металу",
                        "Ґрунтування металу",
                        "Фарбування металу",
                        "Монтаж металоконструкцій",
                        "Анкерування опорних пластин");
    }

    @Test
    void everyLineFindsItsCatalogPosition() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) """ + BUNDLE + """
                   AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                                    WHERE c.trade = 'METAL' AND c.type = 'WORK' AND c.unit = i.unit
                                      AND lower(trim(c.name)) = lower(trim(i.name)))
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM estimate_templates
                 WHERE owner_id IS NULL AND is_default AND trade = 'METAL'
                   AND description IS NOT NULL AND name = 'Антресоль / майданчик'
                """, Integer.class)).isOne();
    }
}
