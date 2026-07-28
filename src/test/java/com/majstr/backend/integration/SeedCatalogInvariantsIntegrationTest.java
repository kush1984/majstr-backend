package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default catalog and the default bundles hold together after every migration.
 *
 * <p>These are the properties V70–V73 established, expressed as tests so the next seed import
 * cannot quietly undo them. The tetris import (V50) claimed it had deduplicated and had not: it
 * compared punctuation-insensitively while the rows already in the table had been written with
 * punctuation <em>and</em> connecting words stripped, so one work ended up sold under two names —
 * and, because a bundle references a position BY NAME, the big tetris bundles and the small older
 * ones priced the same job from different rows. Nothing failed; the catalog just quietly doubled.
 * A test is the only thing that catches that shape of damage.</p>
 *
 * <p>Everything here runs against the shared, fully migrated database, so it describes the state a
 * NEW master is seeded from. What happens to a database carrying the marks of its own history is
 * {@link CatalogCleanupOnLegacyDataIntegrationTest}.</p>
 */
class SeedCatalogInvariantsIntegrationTest extends IntegrationTestBase {

    /** The four "categories" the tetris import created that only repeat the trade name. */
    private static final String DRAWERS =
            "'САНТЕХНІКА', 'ЕЛЕКТРИКА', 'ПЛИТОЧНІ РОБОТИ', 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ'";

    @Autowired JdbcTemplate jdbc;

    @Test
    void noPositionIsSoldTwiceUnderTwoWordings() {
        // Punctuation, spacing and the two dimension separators are stripped before comparing:
        // «Укладання плитки 600*600» and «Укладання плитки 600х600» were two rows for one job,
        // and an exact-match check would have called them distinct and seen nothing wrong.
        List<String> duplicates = jdbc.queryForList("""
                SELECT trade || ' | ' || string_agg(name, '  ||  ' ORDER BY name)
                FROM catalog_templates
                GROUP BY trade,
                         regexp_replace(lower(translate(name, '*×', 'хх')),
                                        '[^0-9a-zа-яіїєґ]', '', 'g'),
                         type, unit
                HAVING count(*) > 1
                """, String.class);

        assertThat(duplicates)
                .as("одна робота під двома назвами — саме те, що зробив тетрісний імпорт")
                .isEmpty();
    }

    @Test
    void everyDefaultBundlePositionCanBePriced() {
        // A master's catalog is seeded from catalog_templates for THEIR trades only
        // (CatalogTemplateService.seedForUser -> findByTradeIn), and applying a bundle resolves the
        // price by lower(name). A bundle of one trade naming a position that only exists under
        // another therefore lands in the estimate at price 0 — silently, which is the worst part.
        // ГІДРОІЗОЛЯЦІЯ used to be priceless in all 4 of its positions, ФАСАДНІ РОБОТИ in 15 of 19.
        List<String> unpriceable = jdbc.queryForList("""
                SELECT et.trade || ' / ' || et.name || ' -> ' || i.name
                FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id AND et.is_default
                WHERE et.trade IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                                  WHERE lower(trim(c.name)) = lower(trim(i.name))
                                    AND c.trade = et.trade)
                """, String.class);

        assertThat(unpriceable)
                .as("позиція шаблону без рядка в каталозі свого ремесла потрапляє в кошторис з ціною 0")
                .isEmpty();
    }

    @Test
    void bundlesAndCatalogAgreeOnTheUnit() {
        // The bundle preview shows the item's own unit; applying it then overwrites that with the
        // catalog's. Where they disagreed the master was shown м.пог and got м².
        List<String> mismatched = jdbc.queryForList("""
                SELECT et.name || ' / ' || i.name || ': ' || i.unit || ' vs ' || c.unit
                FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id AND et.is_default
                JOIN catalog_templates c ON lower(trim(c.name)) = lower(trim(i.name))
                                        AND (et.trade IS NULL OR c.trade = et.trade)
                WHERE i.unit <> c.unit OR i.type <> c.type
                """, String.class);

        assertThat(mismatched).as("прев'ю шаблону показує одну одиницю, а кошторис отримує іншу").isEmpty();
    }

    @Test
    void dimensionsUseOneSeparator() {
        // 58 rows used «х» and 22 «*», so searching «600х600» could not find «600*600».
        assertThat(jdbc.queryForList(
                "SELECT name FROM catalog_templates WHERE name LIKE '%*%'", String.class))
                .as("роздільник розмірів мусить бути один — «х»").isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT name FROM estimate_template_items WHERE name LIKE '%*%'", String.class))
                .as("та сама назва в шаблонах, інакше зникне збіг із каталогом").isEmpty();
    }

    @Test
    void categoriesAreNotDuplicatedByCaseAndDoNotRepeatTheTrade() {
        // The catalog screen groups by category NAME, so «Кладка» and «КЛАДКА» rendered as two
        // sections of one thing. «ЗІЗ» keeps its caps on purpose — it is an acronym.
        assertThat(jdbc.queryForList("""
                SELECT lower(btrim(category)) || ': ' || string_agg(DISTINCT category, ' | ')
                FROM catalog_templates WHERE category IS NOT NULL
                GROUP BY lower(btrim(category)) HAVING count(DISTINCT category) > 1
                """, String.class))
                .as("дві категорії, що різняться лише регістром, читаються як дві різні секції")
                .isEmpty();

        assertThat(jdbc.queryForList(
                "SELECT DISTINCT category FROM catalog_templates "
                        + "WHERE category = upper(category) AND category ~ '[Ѐ-ӿ]' AND category <> 'ЗІЗ'",
                String.class))
                .as("категорії в CAPS лишились тільки від тетрісу; ЗІЗ — акронім, тому виняток")
                .isEmpty();

        assertThat(jdbc.queryForList(
                "SELECT DISTINCT category FROM catalog_templates WHERE category IN (" + DRAWERS + ")",
                String.class))
                .as("«ЕЛЕКТРИКА» всередині ремесла ELECTRICAL — це не групування, а мішок")
                .isEmpty();
    }

    @Test
    void thePriceCheckStillForbidsZero() {
        // «Захист вхідних дверей картоном» sat at 0.00 because the CHECK had been relaxed from
        // `> 0` to `>= 0`; such a row silently produces a zero line in an estimate. The zero-priced
        // positions that remain are known and listed in seed-audit/potrebuyut-ciny.csv — this test
        // pins the count so a NEW one has to be a deliberate decision, not an accident.
        Integer zeroPriced = jdbc.queryForObject(
                "SELECT count(*) FROM catalog_templates WHERE suggested_price = 0", Integer.class);
        assertThat(zeroPriced)
                .as("позиція з ціною 0 дає нульовий рядок у кошторисі; нових бути не повинно")
                .isNotNull().isLessThanOrEqualTo(1);
    }
}
