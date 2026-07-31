package com.majstr.backend.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V82 rewrites the tiling default catalog; V83 pushes it into the catalogs masters already hold
 * and deletes what we ourselves put there and no longer ship.
 *
 * <p>V83 is the first migration in this project that <b>deletes rows out of a live master's own
 * catalog</b>. Everything before it either changed reference data (harmless by construction —
 * defaults are copied by value) or added. So the assertions that matter here are the ones about
 * what stayed, and they are worth more than the ones about what arrived.</p>
 *
 * <p><b>How.</b> A database migrated to <b>V81</b> — one version before the rebuild — is given
 * two tilers' worth of realistic state and one electrician, then migrated to the head. The old
 * positions used below are <b>planted, not picked</b> from the shipped seed: a test that names a
 * real position asserts against data that is free to change in the next catalog edit, and would
 * then fail for a reason that has nothing to do with the migration.</p>
 */
class TilingCatalogRebuildOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v82";
    private static final String TILER = "88888888-8888-8888-8888-888888888888";
    private static final String SPARKY = "99999999-9999-9999-9999-999999999999";
    private static final String ESTIMATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    /** Ours, at our price, dropped from the rebuilt catalog — the one row V83 may delete. */
    private static final String LEFTOVER = "Стара плиточна позиція яку ми більше не возимо";
    /** The same story, except the master moved the price. Their work now. */
    private static final String REPRICED = "Стара позиція яку майстер переоцінив";
    /** Never came from us at all. */
    private static final String OWN = "Моя власна позиція";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV81_seedMasters_thenUpgrade() throws SQLException {
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + DB;
        db = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("81"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code) VALUES
                  ('%s', 'tiler@test.ua',  'tiler@test.ua',  'x', 'Плиточник',   '+380', 'ФОП', 'TIL1'),
                  ('%s', 'sparky@test.ua', 'sparky@test.ua', 'x', 'Електрик',    '+380', 'ФОП', 'ELE1')
                """.formatted(TILER, SPARKY));
        db.execute("""
                INSERT INTO user_trades (user_id, trade) VALUES ('%s','TILING'), ('%s','ELECTRICAL')
                """.formatted(TILER, SPARKY));

        // Two positions we used to ship. Planted so the test does not depend on which real
        // positions the rebuild happens to drop.
        db.execute("""
                INSERT INTO catalog_templates (id, trade, name, type, unit, suggested_price,
                                               category, added_in_version) VALUES
                  (gen_random_uuid(), 'TILING', '%s', 'WORK', 'M2', 700, 'ПЛИТОЧНІ РОБОТИ', 8),
                  (gen_random_uuid(), 'TILING', '%s', 'WORK', 'M2', 500, 'ПЛИТОЧНІ РОБОТИ', 8)
                """.formatted(LEFTOVER, REPRICED));

        // The tiler's catalog as copyMissing would have left it, plus one row of their own.
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source) VALUES
                  (gen_random_uuid(), '%1$s', '%2$s', 'WORK', 'M2',  700, 'ПЛИТОЧНІ РОБОТИ', 'TILING', 'LIBRARY'),
                  (gen_random_uuid(), '%1$s', '%3$s', 'WORK', 'M2', 1234, 'ПЛИТОЧНІ РОБОТИ', 'TILING', 'LIBRARY'),
                  (gen_random_uuid(), '%1$s', '%4$s', 'WORK', 'M2',  900, 'МОЇ',             'TILING', 'MANUAL')
                """.formatted(TILER, LEFTOVER, REPRICED, OWN));

        // A bundle the master saved themselves, naming a position that is about to vanish.
        db.execute("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', '%s', 'Мій шаблон', 'TILING', false)
                """.formatted(TILER));
        db.execute("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (gen_random_uuid(), 'cccccccc-cccc-cccc-cccc-cccccccccccc', '%s', 'WORK', 'M2', 0)
                """.formatted(LEFTOVER));

        // An estimate written from the position that is about to disappear from the catalog.
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(TILER));
        db.execute("""
                INSERT INTO estimates (id, project_id, status)
                VALUES ('%s', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DRAFT')
                """.formatted(ESTIMATE));
        db.execute("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price)
                VALUES (gen_random_uuid(), '%s', 'WORK', '%s', 'M2', 18, 700)
                """.formatted(ESTIMATE, LEFTOVER));
    }

    private static int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        assertThat(n).isNotNull();
        return n;
    }

    private static int rows(String owner, String name) {
        return count("SELECT count(*) FROM catalog_items WHERE owner_id = ? AND name = ?",
                UUID.fromString(owner), name);
    }

    // =============================================================================================

    @Test
    void theUpgradeCompletes() {
        assertThat(db.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class))
                .isEmpty();
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history WHERE success"))
                .isGreaterThanOrEqualTo(83);
    }

    @Test
    void theDefaultCatalogIsTheRebuiltOne() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'TILING'"))
                .isEqualTo(167);
        assertThat(count("SELECT count(DISTINCT category) FROM catalog_templates WHERE trade = 'TILING'"))
                .isEqualTo(11);
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'TILING' AND type <> 'WORK'"))
                .as("матеріалів у плиточному каталозі більше немає")
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'TILING' AND added_in_version <> 9
                """))
                .as("уся плитка приїхала однією версією — інакше синхронізація її не побачить")
                .isZero();
    }

    @Test
    void theScratchTableIsGone() {
        assertThat(count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name = 'tiling_v9_baseline'
                """))
                .as("тимчасова таблиця V82 має бути прибрана за собою в V83")
                .isZero();
    }

    // ---- what arrived ---------------------------------------------------------------------------

    @Test
    void theTilerGotTheWholeNewCatalog() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates ct
                WHERE ct.trade = 'TILING' AND NOT EXISTS (
                    SELECT 1 FROM catalog_items ci
                    WHERE ci.owner_id = ?
                      AND lower(trim(ci.name)) = lower(trim(ct.name))
                      AND ci.type = ct.type AND ci.unit = ct.unit)
                """, UUID.fromString(TILER)))
                .as("жодна нова позиція не мала лишитись недоставленою")
                .isZero();
    }

    @Test
    void theElectricianGotNothing() {
        assertThat(count("SELECT count(*) FROM catalog_items WHERE owner_id = ?",
                UUID.fromString(SPARKY)))
                .as("плиточний каталог не має протікати в чужий трейд")
                .isZero();
    }

    @Test
    void theSyncedVersionAdvancedForTheTilerOnly() {
        assertThat(db.queryForObject("SELECT last_synced_catalog_version FROM users WHERE id = ?",
                Integer.class, UUID.fromString(TILER)))
                .as("бейдж «N нових позицій» не має світитись за роботу, яку вже зробили за майстра")
                .isEqualTo(9);
        assertThat(db.queryForObject("SELECT last_synced_catalog_version FROM users WHERE id = ?",
                Integer.class, UUID.fromString(SPARKY)))
                .isLessThan(9);
    }

    // ---- what stayed ----------------------------------------------------------------------------

    @Test
    void ourUntouchedLeftoverIsGone() {
        assertThat(rows(TILER, LEFTOVER))
                .as("наша позиція, за нашою ціною, якої вже немає в каталозі")
                .isZero();
    }

    @Test
    void aRepricedLibraryRowStays() {
        assertThat(rows(TILER, REPRICED)).isOne();
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(TILER), REPRICED))
                .as("ціну ставив майстер — рядок його, навіть якщо позиція наша")
                .isEqualByComparingTo("1234");
    }

    @Test
    void theMastersOwnPositionStays() {
        assertThat(rows(TILER, OWN)).as("MANUAL — не наша справа").isOne();
    }

    @Test
    void theEstimateIsUntouched() {
        assertThat(count("SELECT count(*) FROM estimate_items WHERE estimate_id = ?::uuid", ESTIMATE))
                .isOne();
        assertThat(db.queryForObject(
                "SELECT name FROM estimate_items WHERE estimate_id = ?::uuid", String.class, ESTIMATE))
                .as("кошторис написаний позицією, якої вже немає — і це нічого не міняє")
                .isEqualTo(LEFTOVER);
    }

    // ---- and the master is told ------------------------------------------------------------------

    @Test
    void theTilerHasAPendingNotice() {
        assertThat(count("SELECT count(*) FROM catalog_update_notices WHERE user_id = ?",
                UUID.fromString(TILER))).isOne();
        assertThat(count("SELECT positions_removed FROM catalog_update_notices WHERE user_id = ?",
                UUID.fromString(TILER)))
                .as("прибрали рівно один залишок")
                .isOne();
        assertThat(count("SELECT positions_added FROM catalog_update_notices WHERE user_id = ?",
                UUID.fromString(TILER)))
                .isEqualTo(167);
        assertThat(count("SELECT count(*) FROM catalog_update_notices WHERE dismissed_at IS NOT NULL"))
                .as("повідомлення ще ніхто не бачив")
                .isZero();
    }

    // ---- V84, the bundles ------------------------------------------------------------------------

    @Test
    void theTilingBundlesWereReplaced() {
        assertThat(count("SELECT count(*) FROM estimate_templates WHERE is_default AND trade = 'TILING'"))
                .as("9 основних + 2 нішевих + «УСІ ПЛИТОЧНІ РОБОТИ»")
                .isEqualTo(12);
        assertThat(count("""
                SELECT count(*) FROM estimate_templates
                WHERE is_default AND trade = 'TILING' AND name = 'УСІ ПЛИТОЧНІ РОБОТИ'
                """)).isOne();
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.name = 'УСІ ПЛИТОЧНІ РОБОТИ'
                """))
                .as("каталог-усе має містити весь трейд")
                .isEqualTo(167);
    }

    @Test
    void noBundleLineIsOrphaned() {
        assertThat(db.queryForList("""
                SELECT DISTINCT ti.name FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.trade = 'TILING'
                  AND NOT EXISTS (
                      SELECT 1 FROM catalog_templates ct
                      WHERE ct.trade = 'TILING'
                        AND lower(trim(ct.name)) = lower(trim(ti.name))
                        AND ct.type = ti.type AND ct.unit = ti.unit)
                """, String.class))
                .as("рядок шаблону, який не резолвиться в каталог, назавжди коштує 0")
                .isEmpty();
    }

    @Test
    void everyBundleCarriesTheAlwaysBilledFour() {
        for (String position : new String[] {
                "Укриття плівкою підлоги, дверей, сантехніки",
                "Винесення та вивезення будівельного сміття",
                "Прибирання приміщення після робіт",
                "Гарантійний повторний виїзд"}) {
            assertThat(count("""
                    SELECT count(*) FROM estimate_templates t
                    WHERE t.is_default AND t.trade = 'TILING'
                      AND NOT EXISTS (SELECT 1 FROM estimate_template_items ti
                                      WHERE ti.template_id = t.id AND ti.name = ?)
                    """, position))
                    .as("«%s» має бути в кожному шаблоні — це гроші, які майстер забуває виставити",
                            position)
                    .isZero();
        }
    }

    @Test
    void theMastersOwnBundleIsUntouched() {
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items
                WHERE template_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
                """))
                .as("шаблон майстра лишається як був, навіть якщо називає зниклу позицію")
                .isOne();
        assertThat(count("""
                SELECT count(*) FROM estimate_templates WHERE id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
                """)).isOne();
    }

    @Test
    void theElectricianHasNoNotice() {
        assertThat(count("SELECT count(*) FROM catalog_update_notices WHERE user_id = ?",
                UUID.fromString(SPARKY)))
                .as("нічого не змінилось — нема про що повідомляти")
                .isZero();
    }
}
