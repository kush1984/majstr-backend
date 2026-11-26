package com.majstr.backend.integration;

import com.majstr.backend.dto.RegionalCatalogPricesResponse;
import com.majstr.backend.dto.RegionalCatalogPricesResponse.Row;
import com.majstr.backend.entity.Region;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.service.RegionalCatalogPriceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * «Ціни по областях»: another oblast's figure for each work in his catalog — the oblast's median, else
 * the country baseline × that oblast's factor — beside the country figure; PRO only.
 */
class RegionalCatalogPriceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RegionalCatalogPriceService service;

    private final UUID userId = UUID.randomUUID();

    private void master(String plan) {
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, region, plan)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, 'LVIV', ?)
                """, userId, userId + "@test.ua", userId + "@test.ua", "RC" + userId.toString().substring(0, 6), plan);
    }

    private UUID work(String name, String unit, int price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, trade, source)
                VALUES (?, ?, ?, 'WORK', ?, ?, 'BUILDER', 'MANUAL')
                """, id, userId, name, unit, price);
        return id;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM regional_price");
        jdbc.update("DELETE FROM regional_baseline");
        jdbc.update("DELETE FROM regional_index");
        jdbc.update("DELETE FROM catalog_items WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void anotherOblast_medianThenEstimate_besideTheCountry() {
        master("PRO");
        UUID median = work("Монтаж мауерлату", "LINEAR_METER", 150);
        UUID estimated = work("Кладка перегородок", "M2", 300);
        UUID libraryOnly = work("мауерлату МОНТАЖ", "LINEAR_METER", 160); // same key, his own spelling
        UUID nothing = work("Щось зовсім своє", "PIECE", 100);
        UUID percent = work("Накладні витрати", "PERCENT", 5);

        jdbc.update("""
                INSERT INTO regional_price (id, region, name_key, sample_name, item_type, unit, median_price,
                                            masters_count, min_price, max_price, computed_at)
                VALUES (gen_random_uuid(), 'KYIV_CITY', 'мауерлату монтаж', 'Монтаж мауерлату', 'WORK',
                        'LINEAR_METER', 240, 5, 200, 300, now())
                """);
        jdbc.update("""
                INSERT INTO regional_baseline (id, name_key, sample_name, item_type, unit, median_price,
                                               regions_count, computed_at)
                VALUES (gen_random_uuid(), 'кладка перегородок', 'Кладка перегородок', 'WORK', 'M2', 400, 4, now())
                """);
        jdbc.update("""
                INSERT INTO regional_index (region, factor, masters_count, positions_count, computed_at)
                VALUES ('KYIV_CITY', 1.183, 6, 50, now())
                """);

        RegionalCatalogPricesResponse r = service.forRegion(userId, Region.KYIV_CITY);
        Map<UUID, Row> rows = r.items().stream().collect(Collectors.toMap(Row::itemId, Function.identity()));

        assertThat(r.region()).isEqualTo(Region.KYIV_CITY);
        assertThat(r.factor()).isEqualByComparingTo("1.183");
        assertThat(rows.get(median)).satisfies(row -> {
            assertThat(row.level()).isEqualTo("OBLAST");
            assertThat(row.regionalPrice()).isEqualByComparingTo("240");
            assertThat(row.mastersCount()).isEqualTo(5);
            assertThat(row.countryPrice()).isEqualByComparingTo("170"); // no baseline → the library (V224)
        });
        assertThat(rows.get(estimated)).satisfies(row -> {
            assertThat(row.level()).isEqualTo("OBLAST_ESTIMATE");
            assertThat(row.regionalPrice()).isEqualByComparingTo("470"); // 400 × 1,183 = 473,2 → 470
            assertThat(row.countryPrice()).isEqualByComparingTo("400");
        });
        assertThat(rows.get(libraryOnly).level()).isEqualTo("OBLAST"); // matched by name key
        assertThat(rows).doesNotContainKeys(nothing, percent);
    }

    @Test
    void anOblastWithNoDataYet_stillGivesTheCountry_withNoOblastFigure() {
        master("PRO");
        UUID id = work("Монтаж мауерлату", "LINEAR_METER", 150);

        RegionalCatalogPricesResponse r = service.forRegion(userId, Region.ZAKARPATTIA);

        assertThat(r.factor()).isNull();
        assertThat(r.items()).singleElement().satisfies(row -> {
            assertThat(row.itemId()).isEqualTo(id);
            assertThat(row.level()).isNull();
            assertThat(row.regionalPrice()).isNull();
            assertThat(row.countryPrice()).isEqualByComparingTo("170");
        });
    }

    @Test
    void anOldLibraryWordingHeStillCarries_showsTheNewWordingsFigures() {
        master("PRO");
        UUID kept = work("Монтаж заміна стояка пластик на пластик одна панель", "PIECE", 2800);
        BigDecimal survivor = jdbc.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                WHERE name = 'Монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель'
                  AND unit = 'PIECE'""", BigDecimal.class);

        RegionalCatalogPricesResponse r = service.forRegion(userId, Region.LVIV);

        assertThat(r.items()).anySatisfy(row -> {
            assertThat(row.itemId()).isEqualTo(kept);
            assertThat(row.countryPrice()).isEqualByComparingTo(survivor);
        });
    }

    @Test
    void freeIsRefused() {
        master("FREE");

        assertThatThrownBy(() -> service.forRegion(userId, Region.KYIV_CITY))
                .isInstanceOf(FeatureNotAvailableException.class);
    }
}
