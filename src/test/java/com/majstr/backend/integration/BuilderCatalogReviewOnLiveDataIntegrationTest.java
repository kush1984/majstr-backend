package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V162 — BUILDER's catalog pass, read back off the applied schema. The rename machinery itself is
 * pinned on planted masters by {@link DrywallCatalogReviewOnLiveDataIntegrationTest}.
 */
class BuilderCatalogReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    /** V151's brick norms follow the rename, and stay on the old key for estimates already written. */
    @Test
    void aBrickWallNamedByTheUkrainianBrickStillBuysItsBricks() {
        for (List<String> pair : List.of(
                List.of("кладка цегли 240 мм", "кладка стін з рядової цегли в 1 цеглу (250 мм)"),
                List.of("кладка перегородки з цегли до 50м2", "кладка перегородки з цегли в пів цегли (120 мм) до 50 м²"))) {
            int old = count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?", pair.get(0));
            assertThat(old).as(pair.get(0)).isPositive();
            assertThat(count("SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?", pair.get(1)))
                    .as(pair.get(1)).isEqualTo(old);
        }
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE name = 'Кладка цегли 240 мм'")).isZero();
        assertThat(count("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V162'")).isEqualTo(20);
    }

    @Test
    void theSmallScreedIsOneRowTwoTradesReach() {
        assertThat(jdbc.queryForList("SELECT trade FROM catalog_templates WHERE name = 'Цементно-піщана стяжка до 20м2' ORDER BY trade",
                String.class)).containsExactly("BUILDER", "FLOORING");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT coalesce(trade, 'any') FROM material_norm
                 WHERE owner_id IS NULL AND name_key = 'цементно-піщана стяжка до 20м2'""", String.class))
                .containsExactly("any");
    }

    @Test
    void theNewPositionsLandedAndTheMixerPourBuysConcrete() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'CONCRETE_READY'
                   AND n.name_key = 'бетонування фундаменту з міксера (приймання, вібрування)'""")).isOne();
        assertThat(count("""
                SELECT count(*) FROM catalog_templates WHERE trade = 'BUILDER'
                   AND name IN ('Влаштування армопояса по газоблоку в U-блоках',
                                'Кладка стін з керамоблоку 380 мм на теплий розчин або клей-піну',
                                'Напівсуха стяжка механізована (з фіброю)')""")).isEqualTo(3);
    }

    /** V120 recorded the two doorway rows as one job under two wordings: they are not renamed apart. */
    @Test
    void theDoorwayPairIsLeftForTheDuplicatesRuling() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates WHERE trade = 'BUILDER'
                   AND name IN ('Облаштування дверної пройми', 'Облаштування дверного пройому звуження розширення')"""))
                .isEqualTo(2);
    }
}
