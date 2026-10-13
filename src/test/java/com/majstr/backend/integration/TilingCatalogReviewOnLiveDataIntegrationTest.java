package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V159 — TILING's catalog pass, read back off the applied schema. The rename machinery itself (a
 * master's copies, forks, notices) is pinned on planted masters by
 * {@link DrywallCatalogReviewOnLiveDataIntegrationTest}; this keeps TILING's own outcome.
 */
class TilingCatalogReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    @Test
    void theTyposAndTheBlurredNamesAreGoneFromTheLibraryAndTheBundles() {
        for (String old : List.of("Різ плітки під 45° заусовка", "Виготовлення конструкціїї під інсталяцію з ГКЛ в 2 шари",
                "Вкладання копінгового каменю", "Облицювання мозаїкою обсягів", "Ґрунтівка поверхні",
                "Укладання плитки без шва", "Укладання плитки 1000х1000")) {
            assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'TILING' AND name = ?", old))
                    .as(old).isZero();
            assertThat(count("""
                    SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                     WHERE t.owner_id IS NULL AND i.name = ?""", old)).as(old).isZero();
        }
        assertThat(count("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V159'")).isEqualTo(21);
    }

    /** The old key keeps its norms for estimates already written; the new key answers from today. */
    @Test
    void aRenamedPositionAnswersUnderBothNames() {
        for (String key : List.of("ґрунтівка поверхні", "ґрунтування поверхні",
                "нанесення однокомпонентної гідроізоляції", "нанесення гідроізоляційної мастики у 2 шари")) {
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?", key))
                    .as(key).isPositive();
        }
    }

    @Test
    void theNewPositionsBuyWhatTheyUse() {
        assertThat(jdbc.queryForList("""
                SELECT m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL
                   AND n.name_key = 'гідроізоляція примикання ванни чи піддону до стіни (стрічка, мастика)'
                """, String.class)).containsExactlyInAnyOrder("TAPE_WATERPROOF", "WATERPROOF_MASTIC");
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'WATERPROOF_CORNER'""")).isOne();
        assertThat(count("""
                SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND material_id IS NULL
                   AND name_key = 'підготовка старої плитки під укладання плитки на плитку (шліфування, знежирення)'
                """)).isOne();
    }

    @Test
    void categoriesAndDescriptionsLanded() {
        assertThat(jdbc.queryForObject(
                "SELECT category FROM catalog_templates WHERE trade = 'TILING' AND name = 'Укладання плитки 1000х1000 мм'",
                String.class)).isEqualTo("Укладання широкоформатної плитки");
        assertThat(jdbc.queryForObject(
                "SELECT category FROM catalog_templates WHERE trade = 'TILING' AND name = 'Погодні умови (плюс % до кошторису)'",
                String.class)).isEqualTo("Організаційні послуги");
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                 WHERE trade = 'TILING' AND name ~ '^Укладання плитки [0-9]+х[0-9]+' AND description IS NULL""")).isZero();
    }
}
