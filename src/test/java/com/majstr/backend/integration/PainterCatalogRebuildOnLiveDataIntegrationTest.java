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
 * <p>V109 folds a third master's price list in on the same additive rule: median the price where the
 * position already exists (6 rows), add only what is genuinely missing (14 rows), and append those
 * to whichever bundles already cover that scope (13 lines).</p>
 *
 * <p>V112 then rebuilds the BUNDLES the other way round from V99: the 21 defaults go and three
 * ordered sequences replace them. V99 kept the old ones because they were real curated work; the
 * master's own verdict a fortnight later was that most of them are 3-6 positions in no order at
 * all, and a template is a sequence — what is done after what. Only the bundles move; the catalog
 * keeps every position, including the facade ones whose bundle is gone.</p>
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
    void theCatalogEndsUpNetPlus81() {
        // V96: +79 new, -4 duplicate leftovers. V99: -22 (†split LINEAR_METER halves collapsed
        // away), +11 (organizational services). V109: +14 (the third master's genuinely-new
        // positions; 6 more of their rows repriced in place, 5 dropped as already covered).
        // V112: +2 (the only two positions the three painter price lists still lacked).
        // V116: +1 — a DRYWALL migration, but stage 6 of the finishing matrix it implements is
        // airless painting, which PAINTER carried under no wording. It belongs here, not under
        // drywall, because the Q levels deliberately stop before the paint.
        // Net +81 vs whatever V95 actually shipped.
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE trade = 'PAINTER'"))
                .isEqualTo(painterCatalogBeforeV96 + 81);
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
                WHERE ct.trade = 'PAINTER' AND ct.added_in_version IN (10, 11, 12, 13) AND NOT EXISTS (
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
    void thePainterHasOneNoticePerRound() {
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
        assertThat(count("""
                SELECT count(*) FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND positions_added = 16 AND positions_removed = 0
                """, UUID.fromString(PAINTER)))
                .as("V112's push: its own 2 positions plus the 14 V109 never pushed at all")
                .isOne();
    }

    @Test
    void thePainterSyncedVersionAdvancedToThirteen() {
        assertThat(db.queryForObject("SELECT last_synced_catalog_version FROM users WHERE id = ?",
                Integer.class, UUID.fromString(PAINTER)))
                .isEqualTo(13);
    }

    // ---- V112: 21 unordered bundles replaced by 3 ordered sequences --------------------------------

    @Test
    void exactlyThreeOrderedDefaultBundlesExist() {
        // V99 restored the 19 old bundles because they were "real curated work"; V112 finishes the
        // thought the other way. Most of them carried 3-6 positions in no particular order, and a
        // template is a SEQUENCE — «коли буде заходити майстер на об'єкт і йому треба буде шаблон з
        // 3-х позицій, то він і кошторису на таке не складає» (master, 2026-08-23).
        assertThat(db.queryForList("""
                SELECT name FROM estimate_templates
                WHERE is_default AND trade = 'PAINTER' ORDER BY name
                """, String.class))
                .containsExactly("Малярні роботи", "Фарбування", "Шпаклювання");
    }

    @Test
    void theOldUnorderedBundlesAreGone() {
        // The named ones the master called out by hand: a bag of positions, and a facade bundle
        // nobody assembled on purpose. Their POSITIONS stay in the catalog — only the bundles go.
        assertThat(count("""
                SELECT count(*) FROM estimate_templates
                WHERE is_default AND trade = 'PAINTER'
                  AND name IN ('ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ', 'ФАСАДНІ РОБОТИ', 'ШТУКАТУРКА',
                               'Укоси вікон', 'Приховані двері та тіньові шви')
                """))
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND category = 'Фасад'
                """))
                .as("«з позицій нічого не викидаємо» — фасадні позиції лишаються в каталозі")
                .isPositive();
    }

    @Test
    void eachBundleFollowsItsOwnRunningOrder() {
        // The property that makes these three worth reaching for: sort_order is the order the work
        // is actually done in. Spot-checked on the master's own numbered 1-19 cycle — sanding and
        // dedusting precede priming, fibreglass precedes the finish coat, paint comes last.
        assertOrdered("Малярні роботи", "Шліфування штукатурки", "Обезпилення поверхні",
                "Грунтування", "Базове шпаклювання під скловолокно",
                "Армування стін скловолокном (склохолст)", "Шпаклювання фінішне (2–4 рази)",
                "Грунт-фарба (праймер під фарбу)", "Фарбування стін/стель (білий)");
        assertOrdered("Шпаклювання", "Закидання штраб (ел/сант)", "Грунтування",
                "Шпаклювання стін (старт, за потреби)", "Армування стін скловолокном (склохолст)",
                "Шпаклювання фінішне (2–4 рази)", "Шліфування стін/стель (фінішне)");
        assertOrdered("Фарбування", "Обклеювання приміщення (захист)", "Обезпилення поверхні",
                "Грунт-фарба (праймер під фарбу)", "Фарбування стін/стель (білий)",
                "Фарбування молдинга/багета до 6 см");
    }

    @Test
    void everyBundleIsBigEnoughToBeWorthApplying() {
        // 3-4 positions was the shape the master rejected. Nothing here enforces a magic number in
        // the app — this pins the intent so the next seed cannot quietly shrink them back.
        for (String bundle : new String[] {"Малярні роботи", "Шпаклювання", "Фарбування"}) {
            assertThat(itemCountOf(bundle)).as("«%s»", bundle).isGreaterThanOrEqualTo(25);
        }
    }

    @Test
    void theTwoNewCatalogPositionsShipAndAreUsed() {
        assertPrice("LINEAR_METER", "Армування врізних трекових світильників/вентиляційних дифузорів", 360);
        assertPrice("LINEAR_METER", "Шпаклювання стін (старт, за потреби) до 60 см", 260);
        assertBundleCarries("Шпаклювання", "Армування врізних трекових світильників/вентиляційних дифузорів");
        // The start putty as two positions, m² and running metre. They may NOT share a name: the
        // price join and the multi-template dedup are both purely on lower(trim(name)), so a
        // same-named pair silently loses one half — the scope qualifier is what keeps them apart.
        assertBundleCarries("Шпаклювання", "Шпаклювання стін (старт, за потреби)");
        assertBundleCarries("Шпаклювання", "Шпаклювання стін (старт, за потреби) до 60 см");
    }

    @Test
    void theRegisteredPainterGotTheTwoNewPositions() {
        // itemRows matches on lower(trim(name)) — the same key the price join uses.
        assertThat(itemRows(PAINTER,
                "Армування врізних трекових світильників/вентиляційних дифузорів".toLowerCase()))
                .isOne();
        assertThat(itemRows(PAINTER, "Шпаклювання стін (старт, за потреби) до 60 см".toLowerCase()))
                .isOne();
    }

    /** Asserts the named positions appear in this bundle in exactly this relative order. */
    private void assertOrdered(String bundle, String... positions) {
        assertThat(db.queryForList("""
                SELECT ti.name FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.trade = 'PAINTER' AND t.name = ?
                ORDER BY ti.sort_order
                """, String.class, bundle))
                .as("«%s» — послідовність, а не набір", bundle)
                .containsSubsequence(positions);
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

    // ---- V109: a third master's price list folded in ----------------------------------------------

    @Test
    void theSixOverlappingPositionsAreRepricedToTheMedian() {
        // 75 was V109's median of the two source lists. V120 raised it to 100 on the master's own
        // word — DRYWALL sells the identical job («Заповнення та армування стиків ГКЛ») at 100, and
        // his own catalog row already read 100 before he said it.
        assertPrice("LINEAR_METER", "Армування стиків ГКЛ", 100);
        assertPrice("LINEAR_METER", "Монтаж шпаклювальних кутиків", 110);
        assertPrice("M2", "Грунтовка поверхонь перед штукатуркою армуванням", 35);
        assertPrice("LINEAR_METER", "Поклейка стрічки «американка»", 110);
        assertPrice("LINEAR_METER", "Закидання штраб (ел/сант)", 108);
        assertPrice("M2", "Грунтування", 33);
    }

    @Test
    void thePositionsAlreadyAtTheSourcePriceAreUntouched() {
        assertPrice("LINEAR_METER", "Штукатурка укосів", 350);
        assertPrice("M2", "Штукатурні роботи (від)", 350);
        assertPrice("M2", "Фарбування стін/стель (у кольорі)", 180);
    }

    @Test
    void theFourteenNewPositionsExistExactlyOnce() {
        for (String name : new String[] {
                "Грунтування укосів",
                "Обезпилення та грунтування укосів перед фарбуванням",
                "Фарбування укосів",
                "Штукатурка криволінійних площин",
                "Підготовка криволінійних площин під скловолокно",
                "Приклеювання скловолокна на криволінійні площини",
                "Шпаклювання криволінійних площин 3 рази зі шліфуванням",
                "Монтаж ГКЛ у кілька шарів на укоси дверей прихованого монтажу та примикання",
                "Шпаклювання з армуванням навколо дверей прихованого монтажу",
                "Шпаклювання швів ГКЛ та шурупів зі шліфуванням",
                "Місцевий ремонт цементно-вапняної штукатурки (перетяжка)",
                "Шліфування бетонних стін та стель від напливів бетону",
                "Шліфування торців бетонних колон від напливів бетону",
                "Лакування бетонних стін"}) {
            assertThat(count("""
                    SELECT count(*) FROM catalog_templates
                    WHERE trade = 'PAINTER' AND lower(trim(name)) = lower(trim(?))
                    """, name))
                    .as("«%s» must exist exactly once in the default PAINTER catalog", name)
                    .isEqualTo(1);
        }
    }

    @Test
    void thePositionsCoveredByAnExistingRowWereNotAdded() {
        // The source's «відкоси» wording must never land in the catalog — this vocabulary uses «укоси»
        // (see V109's header note), and a second spelling would break the by-name template→price join.
        assertThat(count("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'PAINTER' AND lower(trim(name)) LIKE '%відкос%'
                """))
                .isZero();
    }

    private void assertPrice(String unit, String name, int expected) {
        assertThat(db.queryForObject("""
                SELECT suggested_price FROM catalog_templates
                WHERE trade = 'PAINTER' AND unit = ? AND name = ?
                """, BigDecimal.class, unit, name))
                .as("«%s» (%s)", name, unit)
                .isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    private void assertBundleCarries(String bundle, String position) {
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND t.trade = 'PAINTER' AND t.name = ? AND ti.name = ?
                """, bundle, position))
                .as("«%s» must carry «%s»", bundle, position)
                .isEqualTo(1);
    }
}
