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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V96 extends the PAINTER default catalog (unlike V82's tiling rebuild, it does NOT wipe the trade
 * first). V97 pushes that into masters who already registered, baseline-protected like V83.
 *
 * <p>V98 then made a real mistake: it replaced the 19 pre-existing PAINTER bundles with 6 new
 * phase-ordered ones, on the theory that templates are curated (not reference data) the same way
 * V84 replaced tiling's — but the old 19 were themselves real, non-trivial bundles (one alone
 * carried 61 lines), so the DELETE lost real curated work. Caught immediately after building
 * locally; V99 restores the 19 and folds the new phases into whichever of them already covers that
 * scope, only creating a genuinely new bundle where nothing did. V99 also collapses the †split
 * M2/LINEAR_METER pairs V96 created down to one M2 row each (a suffix-disambiguated same-named
 * pair breaks {@code EstimateTemplateService}'s purely-by-name lookup/dedup), and adds an
 * "Організаційні послуги" category mirroring tiling's, distributed into every default bundle.</p>
 *
 * <p>Same discipline as the tiling test: a database migrated to V95, given real master state, then
 * migrated to head — and the 4 historical duplicate-pair positions this migration targets are left
 * for V27/V31/V50 to actually produce, not planted, so content-matching is tested against real
 * migration output.</p>
 */
class PainterCatalogRebuildOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v96";
    private static final String PAINTER = "77777777-7777-7777-7777-777777777777";
    private static final String SPARKY = "66666666-6666-6666-6666-666666666666";
    private static final String PROJECT = "55555555-5555-5555-5555-555555555555";
    private static final String ESTIMATE = "44444444-4444-4444-4444-444444444444";

    /** One of the 4 pre-existing duplicate pairs' lower-priced row, left exactly as shipped — the
     *  plain "leftover" case V97 must clean up. */
    private static final String PLAIN_LEFTOVER = "шліфування стін після штукатурки не нами";
    /** Same family, but the master raised the price — their edit, must survive. */
    private static final String REPRICED = "вирівнювання стін не нами за погодженням";
    /** Same family, but re-typed by the master (MANUAL) — not our business at all. */
    private static final String MANUAL_OWN = "дефектовка стін не нами за погодженням";
    /** Same family, left as shipped, but an estimate already quotes it — must clean up the catalog
     *  copy while leaving the estimate's own snapshot alone. */
    private static final String QUOTED_LEFTOVER = "ошкурення стін після шпаклівки не нами";

    /** One of V96's †split pairs. V99 collapses it to a single M2 row, unsuffixed. */
    private static final String SPLIT_COLLAPSED = "обезпилення поверхні";
    /** A different †split pair, deliberately collided with a pre-existing MANUAL row at the exact
     *  stripped name — see {@link #theOwnRowThatWouldCollideWithAStrippedNameIsUntouched()}. This
     *  reproduces a real failure: the first version of V99's rename crashed the whole migration
     *  (unique constraint violation) the moment any master already had a row at the bare name. */
    private static final String SPLIT_COLLIDING = "вирівнювання стін";

    private static JdbcTemplate db;
    private static int painterCatalogBeforeV96;

    @BeforeAll
    static void migrateToV95_seedMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("95"))
                .load().migrate();

        painterCatalogBeforeV96 = count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER'");
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND type = 'WORK' AND unit = 'M2' AND suggested_price = 60.00
                  AND lower(trim(name)) IN (?, ?, ?, ?)
                """, PLAIN_LEFTOVER, REPRICED, MANUAL_OWN, QUOTED_LEFTOVER))
                .as("the 4 historical duplicate rows this migration targets must actually be present "
                        + "at V95 — otherwise this test is not exercising what V96 does in production")
                .isEqualTo(4);

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code) VALUES
                  ('%s', 'painter@test.ua', 'painter@test.ua', 'x', 'Маляр',    '+380', 'ФОП', 'PNT1'),
                  ('%s', 'sparky@test.ua',  'sparky@test.ua',  'x', 'Електрик', '+380', 'ФОП', 'ELE2')
                """.formatted(PAINTER, SPARKY));
        db.execute("""
                INSERT INTO user_trades (user_id, trade) VALUES ('%s','PAINTER'), ('%s','ELECTRICAL')
                """.formatted(PAINTER, SPARKY));

        // The painter's catalog as CatalogTemplateService.copyMissing would have left it at V95 —
        // one row per default PAINTER position, at our price, source LIBRARY.
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                SELECT gen_random_uuid(), '%s', name, type, unit, suggested_price, category,
                       'PAINTER', 'LIBRARY'
                FROM catalog_templates WHERE trade = 'PAINTER'
                """.formatted(PAINTER));

        // The master repriced one of the 4 — their edit, must survive V97.
        db.execute("""
                UPDATE catalog_items SET default_price = 999
                WHERE owner_id = '%s' AND lower(trim(name)) = '%s'
                """.formatted(PAINTER, REPRICED));

        // The master re-typed another one themselves (MANUAL) — not ours to touch, whatever the
        // price. Delete the LIBRARY copy first so the unique index does not see two rows for it.
        db.execute("""
                DELETE FROM catalog_items WHERE owner_id = '%s' AND lower(trim(name)) = '%s'
                """.formatted(PAINTER, MANUAL_OWN));
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', 'Дефектовка стін не нами за погодженням', 'WORK',
                        'M2', 60.00, 'Моя категорія', 'PAINTER', 'MANUAL')
                """.formatted(PAINTER));

        // A pre-existing MANUAL row sits at the exact bare name V99's rename will try to produce for
        // "Вирівнювання стін (м²)" (pushed moments ago by the copy above, from catalog_templates).
        // The first version of V99 crashed here — ux_catalog_items_owner_name_type_unit — for every
        // master, not just this one, the moment any single one of them had this. Reproduced deliberately
        // rather than trusted to a live incident report.
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', 'Вирівнювання стін', 'WORK', 'M2', 500.00,
                        'Моя категорія', 'PAINTER', 'MANUAL')
                """.formatted(PAINTER));

        // An estimate already quotes the 4th leftover — the catalog copy must go, the quote must not.
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, PAINTER));
        db.execute("""
                INSERT INTO estimates (id, project_id, status) VALUES ('%s', '%s', 'DRAFT')
                """.formatted(ESTIMATE, PROJECT));
        db.execute("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price)
                VALUES (gen_random_uuid(), '%s', 'WORK', 'Ошкурення стін після шпаклівки не нами',
                        'M2', 12, 60)
                """.formatted(ESTIMATE));
    }

    private static int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        assertThat(n).isNotNull();
        return n;
    }

    private static int itemRows(String owner, String nameKey) {
        return count("SELECT count(*) FROM catalog_items WHERE owner_id = ? AND lower(trim(name)) = ?",
                UUID.fromString(owner), nameKey);
    }

    // =============================================================================================

    @Test
    void theUpgradeCompletes() {
        assertThat(db.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class))
                .isEmpty();
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history WHERE success"))
                .isGreaterThanOrEqualTo(99);
    }

    @Test
    void theV97ScratchBaselineTableIsGone() {
        assertThat(count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name = 'painter_v10_removed_baseline'
                """))
                .as("V97 must drop its own baseline scratch table when it is done")
                .isZero();
    }

    // ---- V96: additive, not destructive ------------------------------------------------------------

    @Test
    void theCatalogEndsUpNetPlus64() {
        // V96: +79 new, -4 duplicate leftovers. V99: -22 (†split LINEAR_METER halves collapsed
        // away), +11 (organizational services). Net +64 vs whatever V95 actually shipped.
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER'"))
                .isEqualTo(painterCatalogBeforeV96 + 64);
    }

    @Test
    void theFourDuplicateLeftoversAreGoneFromTheDefaultCatalog() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND type = 'WORK' AND unit = 'M2' AND suggested_price = 60.00
                  AND lower(trim(name)) IN (?, ?, ?, ?)
                """, PLAIN_LEFTOVER, REPRICED, MANUAL_OWN, QUOTED_LEFTOVER))
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND lower(trim(name)) LIKE '%зроблен%не нами%'
                """))
                .as("the KEPT half of each duplicate pair must still be there")
                .isEqualTo(4);
    }

    @Test
    void noNewRowCollidesOnTheDedupKey() {
        assertThat(db.queryForList("""
                SELECT string_agg(name, ' || ') FROM catalog_templates
                WHERE trade = 'PAINTER'
                GROUP BY lower(trim(name)), type, unit HAVING count(*) > 1
                """, String.class))
                .as("no two PAINTER positions may share (name, type, unit)")
                .isEmpty();
    }

    @Test
    void otherTradesAreUntouched() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'TILING'"))
                .isEqualTo(167);
    }

    // ---- V99 part 1: †split pairs collapsed to a single M2 row, unsuffixed ------------------------

    @Test
    void noSplitSuffixSurvivesInTheDefaultCatalog() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER' AND name LIKE '%(м%)%'"))
                .as("(м²)/(м.п.) in a title collides with EstimateTemplateService's by-name lookup")
                .isZero();
    }

    @Test
    void theCollapsedPositionKeepsOnlyTheM2Row() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND lower(trim(name)) = ?
                """, SPLIT_COLLAPSED))
                .isOne();
        assertThat(db.queryForObject("""
                SELECT unit FROM catalog_templates WHERE trade = 'PAINTER' AND lower(trim(name)) = ?
                """, String.class, SPLIT_COLLAPSED))
                .isEqualTo("M2");
    }

    @Test
    void theOwnRowThatWouldCollideWithAStrippedNameIsUntouched() {
        // Reaching this line at all proves the fix: @BeforeAll would have thrown
        // (duplicate key value violates unique constraint) on the version of V99 that renamed
        // unconditionally. The master's own MANUAL row survives exactly as they left it —
        assertThat(db.queryForObject("""
                SELECT source || '|' || default_price FROM catalog_items
                WHERE owner_id = ? AND lower(trim(name)) = ? AND type = 'WORK' AND unit = 'M2'
                """, String.class, UUID.fromString(PAINTER), SPLIT_COLLIDING))
                .isEqualTo("MANUAL|500.00");
        // — and the LIBRARY row that could not rename into it keeps the harmless "(м²)" suffix
        // rather than being silently dropped or overwriting the master's row.
        assertThat(itemRows(PAINTER, SPLIT_COLLIDING + " (м²)")).isOne();
    }

    @Test
    void theNewCatalogRowsAreAllPricedAndWork() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND added_in_version = 10
                  AND (suggested_price <= 0 OR type <> 'WORK')
                """))
                .isZero();
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER' AND added_in_version = 10"))
                .as("22 †split pairs collapsed from 44 rows to 22 : 79 - 22 = 57")
                .isEqualTo(57);
    }

    // ---- V99 part 2: organizational services -------------------------------------------------------

    @Test
    void elevenOrganizationalServicePositionsShipped() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND added_in_version = 11 AND category = 'Організаційні послуги'
                """))
                .isEqualTo(11);
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND added_in_version = 11 AND suggested_price <> 0
                """))
                .as("none of these came from a real price list — shipped at 0, not invented")
                .isZero();
    }

    // ---- V97/V99: what the registered painter's own catalog ends up with ---------------------------

    @Test
    void thePainterGotEveryNewCatalogPosition() {
        assertThat(count("""
                SELECT count(*) FROM catalog_templates ct
                WHERE ct.trade = 'PAINTER' AND ct.added_in_version IN (10, 11) AND NOT EXISTS (
                    SELECT 1 FROM catalog_items ci
                    WHERE ci.owner_id = ?
                      AND lower(trim(ci.name)) = lower(trim(ct.name))
                      AND ci.type = ct.type AND ci.unit = ct.unit)
                """, UUID.fromString(PAINTER)))
                .isZero();
    }

    @Test
    void theElectricianGotNoPainterPositions() {
        assertThat(count("SELECT count(*) FROM catalog_items WHERE owner_id = ? AND trade = 'PAINTER'",
                UUID.fromString(SPARKY)))
                .isZero();
    }

    @Test
    void noSplitSuffixSurvivesInThePaintersOwnCatalog() {
        // Exactly one deliberate exception: SPLIT_COLLIDING's LIBRARY row could not rename into the
        // master's own pre-existing MANUAL row at that bare name — see
        // theOwnRowThatWouldCollideWithAStrippedNameIsUntouched(). Every other split-collapsed
        // position renamed cleanly.
        assertThat(count("SELECT count(*) FROM catalog_items WHERE owner_id = ? AND name LIKE '%(м%)%'",
                UUID.fromString(PAINTER)))
                .isOne();
    }

    @Test
    void thePlainLeftoverIsGone() {
        assertThat(itemRows(PAINTER, PLAIN_LEFTOVER)).as("our leftover, our price, no longer shipped").isZero();
    }

    @Test
    void theRepricedRowSurvivesAtTheMastersPrice() {
        assertThat(itemRows(PAINTER, REPRICED)).isOne();
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND lower(trim(name)) = ?",
                BigDecimal.class, UUID.fromString(PAINTER), REPRICED))
                .as("the master's own price is not ours to overwrite")
                .isEqualByComparingTo("999");
    }

    @Test
    void theManualRowSurvivesRegardlessOfPrice() {
        assertThat(itemRows(PAINTER, MANUAL_OWN)).as("MANUAL — not our business").isOne();
        assertThat(db.queryForObject(
                "SELECT source FROM catalog_items WHERE owner_id = ? AND lower(trim(name)) = ?",
                String.class, UUID.fromString(PAINTER), MANUAL_OWN))
                .isEqualTo("MANUAL");
    }

    @Test
    void theQuotedLeftoverCatalogCopyIsGoneButTheEstimateIsNot() {
        assertThat(itemRows(PAINTER, QUOTED_LEFTOVER)).isZero();
        assertThat(count("SELECT count(*) FROM estimate_items WHERE estimate_id = ?::uuid", ESTIMATE))
                .isOne();
        assertThat(db.queryForObject(
                "SELECT name FROM estimate_items WHERE estimate_id = ?::uuid", String.class, ESTIMATE))
                .as("a quote already written from a position we just removed keeps its own snapshot")
                .isEqualTo("Ошкурення стін після шпаклівки не нами");
    }

    @Test
    void thePainterHasTwoNoticesForTheTwoRounds() {
        assertThat(count("""
                SELECT count(*) FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND positions_added = 79 AND positions_removed = 2
                """, UUID.fromString(PAINTER)))
                .as("V97's push: +79 new positions, -2 leftovers actually cleaned")
                .isOne();
        assertThat(count("""
                SELECT count(*) FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND positions_added = 11 AND positions_removed = 22
                """, UUID.fromString(PAINTER)))
                .as("V99's push: +11 organizational-service positions, -22 collapsed †split halves")
                .isOne();
    }

    @Test
    void thePainterSyncedVersionAdvancedToEleven() {
        assertThat(db.queryForObject("SELECT last_synced_catalog_version FROM users WHERE id = ?",
                Integer.class, UUID.fromString(PAINTER)))
                .isEqualTo(11);
    }

    // ---- V99 part 3: 19 old bundles restored + 2 new, nothing orphaned -----------------------------

    @Test
    void twentyOneDefaultBundlesExist() {
        assertThat(count("SELECT count(*) FROM estimate_templates WHERE is_default AND trade = 'PAINTER'"))
                .isEqualTo(21);
        // The two bundles V98 wrongly deleted-and-replaced-everything-with are gone as standalone
        // templates; their content lives inside the restored bundles instead (folded, not lost).
        assertThat(count("""
                SELECT count(*) FROM estimate_templates
                WHERE is_default AND trade = 'PAINTER'
                  AND name IN ('Стіни під фарбування — повний цикл', 'Фінішне фарбування',
                               'Штукатурка та армування стін', 'Молдинги, багети, декор')
                """))
                .as("V98's standalone phase bundles that got folded into old ones must not survive as their own templates")
                .isZero();
    }

    @Test
    void theTwoGenuinelyNewBundlesExist() {
        assertThat(count("""
                SELECT count(*) FROM estimate_templates
                WHERE is_default AND trade = 'PAINTER'
                  AND name IN ('Захист і підготовка приміщення', 'Приховані двері та тіньові шви')
                """))
                .isEqualTo(2);
    }

    @Test
    void theBiggestOldBundleSurvivedIntact() {
        // ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ — 61 original lines, this is the one V98 would have lost
        // most visibly. +3 for the always-billed additions below = 64.
        assertThat(count("""
                SELECT ti_count FROM (
                  SELECT count(*) AS ti_count FROM estimate_template_items ti
                  JOIN estimate_templates t ON t.id = ti.template_id
                  WHERE t.is_default AND t.trade = 'PAINTER' AND t.name = 'ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ'
                ) x WHERE ti_count = 64
                """))
                .isEqualTo(64);
    }

    @Test
    void theThreeExtendedBundlesGrewByTheNewPhases() {
        // ШТУКАТУРКА: 13 old + 6 folded (phase 2) + 3 always-billed = 22.
        // Стіни під фарбування: 9 old + 15 folded (phase 3 + distinct phase 4) + 3 = 27.
        // Багети молдінги: 3 old + 5 folded (phase 5) + 3 = 11.
        assertThat(itemCountOf("ШТУКАТУРКА")).isEqualTo(22);
        assertThat(itemCountOf("Стіни під фарбування")).isEqualTo(27);
        assertThat(itemCountOf("Багети молдінги")).isEqualTo(11);
    }

    private int itemCountOf(String templateName) {
        return count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.trade = 'PAINTER' AND t.name = ?
                """, templateName);
    }

    @Test
    void noBundleLineIsOrphaned() {
        assertThat(db.queryForList("""
                SELECT DISTINCT ti.name FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.trade = 'PAINTER'
                  AND NOT EXISTS (
                      SELECT 1 FROM catalog_templates ct
                      WHERE ct.trade = 'PAINTER'
                        AND lower(trim(ct.name)) = lower(trim(ti.name))
                        AND ct.type = ti.type AND ct.unit = ti.unit)
                """, String.class))
                .as("a bundle line that does not resolve into the catalog prices at 0 forever")
                .isEmpty();
    }

    @Test
    void everyDefaultBundleCarriesTheThreeAlwaysBilledPositions() {
        for (String position : new String[] {
                "Прибирання приміщення після робіт",
                "Винесення та вивезення будівельного сміття",
                "Гарантійний повторний виїзд"}) {
            assertThat(count("""
                    SELECT count(*) FROM estimate_templates t
                    WHERE t.is_default AND t.trade = 'PAINTER'
                      AND NOT EXISTS (SELECT 1 FROM estimate_template_items ti
                                      WHERE ti.template_id = t.id AND ti.name = ?)
                    """, position))
                    .as("«%s» must be in every default PAINTER bundle", position)
                    .isZero();
        }
    }
}
