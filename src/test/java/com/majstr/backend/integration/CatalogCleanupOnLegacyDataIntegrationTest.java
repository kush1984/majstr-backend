package com.majstr.backend.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations run against a database that carries the marks of its own history — not a clean one.
 *
 * <p><b>Why this exists.</b> {@link FlywayMigrationsIntegrationTest} migrates an EMPTY database, so
 * every row it sees was written by the migrations themselves and is therefore perfectly consistent.
 * The catalog cleanup (V70–V73) passed that test and then failed twice in production, both times on
 * a state only a real database can be in:</p>
 *
 * <ol>
 *   <li><b>trade divergence.</b> V30 back-filled {@code catalog_items.trade} by mapping category →
 *       trade and left the ambiguous rows for V33 to set to {@code OTHER}. So the older wording of a
 *       duplicated position often sits on OTHER while its tetris twin carries the real trade. V73
 *       matched on trade — which the unique index
 *       {@code ux_catalog_items_owner_name_type_unit} does not include — so it saw one row of a
 *       pair, deleted nothing, renamed that row onto the name the other already held, and Postgres
 *       rejected it.</li>
 *   <li><b>admin edits.</b> {@code AdminCatalogTemplateService} lets an admin rename or delete a
 *       default catalog position at any time and nothing re-points the bundles that named it. V71
 *       asserted that every bundle position resolves, which is a property of the seed that a live
 *       database is free to break — two edited positions stopped the application from starting.</li>
 * </ol>
 *
 * <p>Both are reproduced below. A clean-database test cannot express either, which is exactly why
 * neither was caught before a deploy.</p>
 *
 * <p><b>How.</b> A second database in the same container is migrated to V69 — the last release
 * before the cleanup — then given the quirks above, then migrated to the head. So the assertions
 * describe what the cleanup does to data that already existed, which is the only case that matters
 * for an upgrade.</p>
 */
class CatalogCleanupOnLegacyDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_legacy_state";
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    /** One duplicated position: the older wording with «х», the tetris one with «*». */
    private static final String OLD_TILE = "Укладання плитки 600х600";
    private static final String NEW_TILE = "Укладання плитки 600*600";

    private static JdbcTemplate legacy;
    private static int catalogItemsBefore;

    @BeforeAll
    static void migrateALegacyShapedDatabase() throws SQLException {
        String adminUrl = POSTGRES.getJdbcUrl();
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(adminUrl, user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + DB;
        legacy = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        // 1. the state a deployment was actually in before the cleanup shipped
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("69"))
                .load().migrate();

        seedLegacyQuirks();
        catalogItemsBefore = count("SELECT count(*) FROM catalog_items");

        // 2. and now the upgrade. A failure here fails the build, which is the whole point:
        //    every previous failure of this chain surfaced at production startup instead.
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedLegacyQuirks() {
        legacy.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'legacy@test.ua', 'legacy@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'LEG1')
                """.formatted(OWNER));
        legacy.execute("""
                INSERT INTO user_trades (user_id, trade) VALUES
                  ('%1$s','ELECTRICAL'), ('%1$s','TILING'), ('%1$s','PLUMBING'), ('%1$s','BUILDER')
                """.formatted(OWNER));

        // the catalog copy CatalogTemplateService.copyMissing would have made: one row per
        // lower(trim(name))|type|unit, for the master's trades only
        legacy.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category, trade)
                SELECT DISTINCT ON (lower(trim(name)), type, unit) gen_random_uuid(), '%s',
                       name, type, unit, suggested_price, category, trade
                FROM catalog_templates
                WHERE trade IN ('ELECTRICAL','TILING','PLUMBING','BUILDER')
                ORDER BY lower(trim(name)), type, unit, trade
                """.formatted(OWNER));

        // (1) trade divergence — what V30/V33 left behind on the rows that already existed
        legacy.execute("UPDATE catalog_items SET trade = 'OTHER' WHERE name = '" + OLD_TILE + "'");
        // a price the master raised by hand, on the row the cleanup would otherwise drop
        legacy.execute("UPDATE catalog_items SET default_price = 7777 WHERE name = '" + OLD_TILE + "'");
        // A category the master re-filed by hand. It has to sit on a position with no duplicate
        // twin, or the dedupe removes the row and the assertion proves nothing — which is exactly
        // how the first version of this test failed. Picked by the query rather than hardcoded, so
        // it cannot drift out of that condition later: a row still in a drawer category (so the
        // category sync WOULD move it, if the guard did not hold) that no other row duplicates.
        legacy.execute("""
                UPDATE catalog_items SET category = 'МОЯ КАТЕГОРІЯ' WHERE id = (
                  SELECT ci.id FROM catalog_items ci
                  WHERE ci.category = 'ЕЛЕКТРИКА'
                    AND NOT EXISTS (
                      SELECT 1 FROM catalog_items o WHERE o.id <> ci.id
                        AND regexp_replace(lower(translate(o.name, '*×', 'хх')),
                                           '[^0-9a-zа-яіїєґ]', '', 'g')
                          = regexp_replace(lower(translate(ci.name, '*×', 'хх')),
                                           '[^0-9a-zа-яіїєґ]', '', 'g'))
                  ORDER BY ci.name LIMIT 1)
                """);

        // an estimate written before the cleanup, from a position about to be merged away
        legacy.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('22222222-2222-2222-2222-222222222222', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(OWNER));
        legacy.execute("""
                INSERT INTO estimates (id, project_id, status)
                VALUES ('33333333-3333-3333-3333-333333333333',
                        '22222222-2222-2222-2222-222222222222', 'DRAFT')
                """);
        legacy.execute("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price)
                VALUES (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'WORK',
                        '%s', 'M2', 42, 950)
                """.formatted(NEW_TILE));

        // (2) an admin deleted a default position that a bundle names, and nothing re-pointed it
        legacy.execute("""
                DELETE FROM catalog_templates WHERE id = (
                  SELECT c.id FROM catalog_templates c
                  JOIN estimate_templates et ON et.trade = c.trade AND et.is_default
                  JOIN estimate_template_items i ON i.template_id = et.id
                                                AND lower(i.name) = lower(c.name)
                  WHERE c.trade = 'DEMOLITION' LIMIT 1)
                """);
    }

    private static int count(String sql) {
        Integer n = legacy.queryForObject(sql, Integer.class);
        assertThat(n).isNotNull();
        return n;
    }

    // =============================================================================================

    @Test
    void theUpgradeCompletesOnLegacyData() {
        // Reaching this line already proves it — @BeforeAll would have thrown. Stated explicitly
        // because it is the guarantee that was missing: both production failures were the migration
        // refusing to finish, not a wrong result.
        assertThat(legacy.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class))
                .as("жодна міграція не має лишитись незастосованою")
                .isEmpty();
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history WHERE success"))
                .isGreaterThanOrEqualTo(73);
    }

    @Test
    void anAdminDeletionDoesNotStopTheUpgrade() {
        // The position the admin removed is still named by a bundle, so that line cannot be priced.
        // Worth a warning, never a reason to refuse a deploy — which is what V71 used to do.
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id AND et.is_default
                WHERE et.trade IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                                  WHERE lower(c.name) = lower(i.name) AND c.trade = et.trade)
                """))
                .as("осиротіла позиція шаблону лишається — але апгрейд її переживає")
                .isEqualTo(1);
    }

    @Test
    void duplicatesAreGoneFromTheMastersOwnCatalog() {
        assertThat(count("SELECT count(*) FROM catalog_items")).isLessThan(catalogItemsBefore);
        assertThat(legacy.queryForList("""
                SELECT string_agg(name, ' || ') FROM catalog_items
                GROUP BY owner_id, lower(trim(name)), type, unit HAVING count(*) > 1
                """, String.class))
                .as("дублі в каталозі майстра")
                .isEmpty();
        assertThat(legacy.queryForList(
                "SELECT name FROM catalog_items WHERE name LIKE '%*%'", String.class))
                .as("роздільник розмірів приведений і в даних майстра")
                .isEmpty();
    }

    @Test
    void theMastersOwnEditsSurviveTheCleanup() {
        // Within a duplicate group the highest-priced row is kept — the rule V49 already used — so a
        // price the master raised themselves is the one that stays, even though this row was the
        // one whose wording loses.
        assertThat(count("SELECT count(*) FROM catalog_items WHERE default_price = 7777"))
                .as("ціна, яку майстер підняв вручну")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM catalog_items WHERE category = 'МОЯ КАТЕГОРІЯ'"))
                .as("категорія, яку майстер перефайлив вручну, не перезаписується")
                .isEqualTo(1);
    }

    @Test
    void anEstimateWrittenBeforeTheCleanupIsUntouched() {
        // estimate_items copy name, unit and price by value and nothing references catalog_items,
        // so merging a catalog position away cannot reach an estimate. This pins that promise:
        // the line still reads with the OLD wording it was written with, «*» separator and all.
        List<String> lines = legacy.queryForList(
                "SELECT name || ' | ' || quantity || ' | ' || unit_price FROM estimate_items",
                String.class);
        assertThat(lines).containsExactly(NEW_TILE + " | 42.000 | 950.00");
    }

    @Test
    void theDefaultCatalogEndsUpInTheSameShapeAsAFreshInstall() {
        // The same invariants SeedCatalogInvariantsIntegrationTest asserts on a clean database must
        // hold here too — an upgrade and a fresh install have to converge, or masters seeded before
        // and after the cleanup get different catalogs.
        assertThat(legacy.queryForList("""
                SELECT trade || ' | ' || string_agg(name, ' || ' ORDER BY name)
                FROM catalog_templates
                GROUP BY trade, regexp_replace(lower(translate(name, '*×', 'хх')),
                                               '[^0-9a-zа-яіїєґ]', '', 'g'), type, unit
                HAVING count(*) > 1
                """, String.class)).as("дублі в дефолтному каталозі").isEmpty();
        assertThat(legacy.queryForList(
                "SELECT DISTINCT category FROM catalog_templates WHERE category IN "
                        + "('САНТЕХНІКА','ЕЛЕКТРИКА','ПЛИТОЧНІ РОБОТИ','ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ')",
                String.class)).as("мішки-категорії").isEmpty();
        assertThat(legacy.queryForList(
                "SELECT DISTINCT category FROM catalog_templates WHERE category = upper(category) "
                        + "AND category ~ '[Ѐ-ӿ]' AND category <> 'ЗІЗ'",
                String.class)).as("CAPS-категорії крім акроніма ЗІЗ").isEmpty();
    }
}
