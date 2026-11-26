package com.majstr.backend.integration;

import com.majstr.backend.dto.PriceHintResponse;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.service.PriceHintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The price hint under the price field: his oblast's median, else the country baseline × his
 * oblast's factor, else the library's price — and nothing for a name the library does not know.
 */
class PriceHintIntegrationTest extends IntegrationTestBase {

    // A real library position (V224, BUILDER 170 ₴/м.п.), so the COUNTRY level has a row to find.
    private static final String NAME = "Монтаж мауерлату";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceHintService service;

    private final UUID userId = UUID.randomUUID();

    private void master(String region) {
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, region)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, ?)
                """, userId, userId + "@test.ua", userId + "@test.ua", "PH" + userId.toString().substring(0, 6), region);
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'BUILDER')", userId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM regional_price");
        jdbc.update("DELETE FROM regional_baseline");
        jdbc.update("DELETE FROM regional_index");
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void hisOblastsMedianComesFirst_matchedByNameKeyNotSpelling() {
        master("LVIV");
        jdbc.update("""
                INSERT INTO regional_price (id, region, name_key, sample_name, item_type, unit, median_price,
                                            masters_count, min_price, max_price, computed_at)
                VALUES (gen_random_uuid(), 'LVIV', 'мауерлату монтаж', ?, 'WORK', 'LINEAR_METER', 195, 4, 150, 240, now())
                """, NAME);

        // Case and word order differ — the same work under CatalogNameKey.
        Optional<PriceHintResponse> hint = service.hint(userId, "мауерлату МОНТАЖ", Unit.LINEAR_METER);

        assertThat(hint).get().satisfies(h -> {
            assertThat(h.level()).isEqualTo("OBLAST");
            assertThat(h.price()).isEqualByComparingTo("195");
            assertThat(h.mastersCount()).isEqualTo(4);
        });
    }

    @Test
    void withoutAnOblastMedian_theBaselineTimesHisFactor_roundedToTen() {
        master("LVIV");
        jdbc.update("""
                INSERT INTO regional_baseline (id, name_key, sample_name, item_type, unit, median_price, regions_count, computed_at)
                VALUES (gen_random_uuid(), 'мауерлату монтаж', ?, 'WORK', 'LINEAR_METER', 200, 4, now())
                """, NAME);
        jdbc.update("""
                INSERT INTO regional_index (region, factor, masters_count, positions_count, computed_at)
                VALUES ('LVIV', 1.083, 5, 40, now())
                """);

        assertThat(service.hint(userId, NAME, Unit.LINEAR_METER)).get().satisfies(h -> {
            assertThat(h.level()).isEqualTo("OBLAST_ESTIMATE");
            assertThat(h.price()).isEqualByComparingTo("220"); // 216,6 → 220
        });
    }

    @Test
    void noRegionalData_orNoOblast_theLibrarysPrice() {
        master(null);

        assertThat(service.hint(userId, NAME, Unit.LINEAR_METER)).get().satisfies(h -> {
            assertThat(h.level()).isEqualTo("COUNTRY");
            assertThat(h.price()).isEqualByComparingTo("170");
            assertThat(h.region()).isNull();
        });
    }

    /** «По країні» is ONE figure everywhere (owner, 2026-11-26): the country median first — the same as
     *  «Ціни по областях» shows — and the library only when there is none. */
    @Test
    void noOblastFigure_butACountryMedian_isTheCountryFigure_notTheLibrarys() {
        master("LVIV"); // his oblast has no median and no factor
        jdbc.update("""
                INSERT INTO regional_baseline (id, name_key, sample_name, item_type, unit, median_price, regions_count, computed_at)
                VALUES (gen_random_uuid(), 'мауерлату монтаж', ?, 'WORK', 'LINEAR_METER', 185, 3, now())
                """, NAME);

        assertThat(service.hint(userId, NAME, Unit.LINEAR_METER)).get().satisfies(h -> {
            assertThat(h.level()).isEqualTo("COUNTRY");
            assertThat(h.price()).isEqualByComparingTo("185"); // not the library's 170
        });
    }

    @Test
    void anUnknownName_aWrongUnit_orAPercent_getNoHint() {
        master("LVIV");

        assertThat(service.hint(userId, "Монтаж чогось зовсім свого", Unit.LINEAR_METER)).isEmpty();
        assertThat(service.hint(userId, NAME, Unit.M2)).isEmpty();
        assertThat(service.hint(userId, NAME, Unit.PERCENT)).isEmpty();
    }

    @Test
    void aWordingTheLibraryRenamed_getsTheNewWordingsFigure() {
        // V167 merged this wording into «Монтаж (заміна) стояка каналізації …»; masters who owned both
        // kept the old copy, and it used to get no hint at all.
        master(null);
        BigDecimal survivor = jdbc.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                WHERE name = 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель'
                  AND unit = 'PIECE'""", BigDecimal.class);

        assertThat(service.hint(userId, "Монтаж заміна стояка пластик на пластик одна панель", Unit.PIECE))
                .get().satisfies(h -> {
                    assertThat(h.level()).isEqualTo("COUNTRY");
                    assertThat(h.price()).isEqualByComparingTo(survivor);
                });
    }
}
