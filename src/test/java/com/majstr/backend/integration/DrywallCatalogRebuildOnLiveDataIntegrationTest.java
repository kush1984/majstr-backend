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
 * V116 rebuilds the DRYWALL catalog: eight jobs that were sold under two wordings collapse to one,
 * masonry leaves the trade, the glued joints position splits, and the finishing chain arrives.
 *
 * <p>The interesting half is what it does to catalogs that already exist, and a normal test run
 * never sees one — the shared database is migrated before any row is written. So this uses the
 * "second database migrated to the version before the change" pattern
 * ({@link TilingCatalogRebuildOnLiveDataIntegrationTest} establishes it,
 * {@link PaymentReceiptMigrationOnLiveDataIntegrationTest} repeats it): migrate to V115, plant the
 * shapes that matter, then finish migrating and look.
 *
 * <p>The shape that specifically needs a live database is the <b>trade-aware delete guard</b>.
 * V83 and V97 asked only "does catalog_templates still ship this name anywhere" — no trade filter,
 * which was fine while the retired rows belonged to one trade. Every masonry position V116 removes
 * from DRYWALL is still shipped under BUILDER at the identical price, so the old guard would have
 * refused every masonry deletion while the migration still reported success. Two masters are
 * planted for exactly that: one with DRYWALL alone, one with DRYWALL and BUILDER. The first must
 * lose his masonry, the second must keep it.
 */
class DrywallCatalogRebuildOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v116";

    /** DRYWALL only — loses masonry, loses the retired wordings, gains the finishing chain. */
    private static final String SOLO = "dddddddd-0000-0000-0000-000000000001";
    /** DRYWALL + BUILDER — keeps his masonry, because BUILDER still ships it. */
    private static final String BOTH = "dddddddd-0000-0000-0000-000000000002";

    /** A retired wording, still carrying OUR price: ours to remove. */
    private static final String RETIRED_AT_OUR_PRICE = "Монтаж радіусної перегородки ГКЛ в 1 шар";
    /** A retired wording, RE-PRICED by the master: his number, so his row. */
    private static final String RETIRED_REPRICED = "Монтаж перегородки ГКЛ 2 сторони в 1 шар";
    /** A retired wording, but typed by the master himself (source MANUAL): never ours to touch. */
    private static final String RETIRED_BUT_HIS = "Заробка стиків у гіпсокартоні поклейка серпянки";
    private static final String MASONRY = "Кладка перегородки з цегли до 50м2";
    private static final String CANON_RADIUS =
            "Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV115_seedTwoMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("115"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        master(SOLO, "solo", "DRY1");
        master(BOTH, "both", "DRY2");
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', 'DRYWALL')".formatted(SOLO));
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', 'DRYWALL'), ('%s', 'BUILDER')"
                .formatted(BOTH, BOTH));

        // Copied from the library, price untouched -> the migration owns these.
        libraryItem(SOLO, RETIRED_AT_OUR_PRICE, "M2", "240.00");
        libraryItem(SOLO, MASONRY, "M2", "900.00");
        libraryItem(BOTH, MASONRY, "M2", "900.00");
        // Copied from the library, then RE-PRICED by the master (our seed price is 650).
        libraryItem(SOLO, RETIRED_REPRICED, "M2", "700.00");
        // Same wording, but he typed it himself.
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, trade, source)
                VALUES (gen_random_uuid(), '%s', '%s', 'WORK', 'LINEAR_METER', 555.00, 'DRYWALL', 'MANUAL')
                """.formatted(SOLO, RETIRED_BUT_HIS));

        // A V113 fork: his own copy of one of OUR default bundles, whose line still names a wording
        // that is about to be retired. The text is ours, so the rename has to reach it — otherwise
        // his line applies at 0 UAH the next time he uses it.
        String fork = UUID.randomUUID().toString();
        String hidden = db.queryForObject(
                "SELECT id FROM estimate_templates WHERE is_default AND trade = 'DRYWALL' "
                        + "AND name = 'Радіусна перегородка'", String.class);
        db.execute("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES ('%s', '%s', 'Радіусна перегородка', 'DRYWALL', false)
                """.formatted(fork, SOLO));
        db.execute("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', 'WORK', 'M2', 0)
                """.formatted(fork, RETIRED_AT_OUR_PRICE));
        db.execute("""
                INSERT INTO template_default_override (user_id, template_id, forked_template_id)
                VALUES ('%s', '%s', '%s')
                """.formatted(SOLO, hidden, fork));

        // A template he wrote from scratch, naming the same retired wording. Never ours to edit.
        String own = UUID.randomUUID().toString();
        db.execute("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES ('%s', '%s', 'Мій шаблон', 'DRYWALL', false)
                """.formatted(own, SOLO));
        db.execute("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', 'WORK', 'M2', 0)
                """.formatted(own, RETIRED_AT_OUR_PRICE));
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 13)
                """.formatted(id, slug, slug, code));
    }

    private static void libraryItem(String owner, String name, String unit, String price) {
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, trade, source)
                VALUES (gen_random_uuid(), '%s', '%s', 'WORK', '%s', %s, 'DRYWALL', 'LIBRARY')
                """.formatted(owner, name, unit, price));
    }

    private static boolean owns(String owner, String name) {
        Integer n = db.queryForObject(
                "SELECT count(*) FROM catalog_items WHERE owner_id = ? AND name = ?",
                Integer.class, UUID.fromString(owner), name);
        return n != null && n > 0;
    }

    // =============================================================================================
    // The catalog itself
    // =============================================================================================

    @Test
    void theEightDoubleSoldJobsCollapseToOneWordingEach() {
        List<String> retired = db.queryForList("""
                SELECT name FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name IN (
                    'Монтаж короба прямого по периметру стелі',
                    'Монтаж короба радіусного по периметру стелі',
                    'Радіусний короб ГКЛ по периметру',
                    'Монтаж перегородки ГКЛ 2 сторони в 1 шар',
                    'Монтаж перегородки ГКЛ 2 сторони в 2 шари',
                    'Монтаж радіусної перегородки ГКЛ в 1 шар',
                    'Монтаж радіусної перегородки ГКЛ в 2 шари',
                    'Монтаж на висоті більше 3м надбавка')
                """, String.class);
        List<String> canon = db.queryForList("""
                SELECT name FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name IN (
                    'Монтаж короба (прямого) із гіпсокартону по периметру стелі',
                    'Монтаж короба (радіусного) із гіпсокартону по периметру стелі',
                    'Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар',
                    'Монтаж радіусних конструкцій (перегородки) із гіпсокартону в 1 шар',
                    'Монтаж на висоті (більше 3м)')
                """, String.class);

        assertThat(retired).as("дві назви на одну роботу — 240 ₴ або 900 ₴ залежно від шаблону").isEmpty();
        assertThat(canon).as("канонічне (тетрісне) формулювання лишається").hasSize(5);
    }

    @Test
    void theThreeHundredAndFortyFivePercentSurchargeIsGone() {
        // V31 wrote a money price into a PERCENT row, so picking «надбавка за висоту» added 345 %
        // to the estimate. It dies with the merge, not by a separate patch — the tetris twin was
        // already the correct 20 %.
        List<BigDecimal> percents = db.queryForList(
                "SELECT suggested_price FROM catalog_templates WHERE trade = 'DRYWALL' AND unit = 'PERCENT'",
                BigDecimal.class);

        assertThat(percents).hasSize(1);
        assertThat(percents.get(0)).isEqualByComparingTo("20.00");
    }

    @Test
    void masonryIsNoLongerADrywallPositionAndTheGluedRowIsSplit() {
        assertThat(db.queryForList(
                "SELECT name FROM catalog_templates WHERE trade = 'DRYWALL' AND category = 'Кладка'",
                String.class)).isEmpty();
        assertThat(db.queryForObject(
                "SELECT count(*) FROM catalog_templates WHERE trade = 'BUILDER' AND name = ?",
                Integer.class, MASONRY))
                .as("кладка не зникає з продукту — вона просто не гіпсокартон").isEqualTo(1);

        assertThat(db.queryForList(
                "SELECT name FROM catalog_templates WHERE trade = 'DRYWALL' AND name LIKE 'Заробка стиків%'",
                String.class))
                .as("три операції у двох різних одиницях під однією ціною").isEmpty();
        assertThat(db.queryForList("""
                SELECT name || ' ' || unit FROM catalog_templates WHERE trade = 'DRYWALL'
                AND name IN ('Заповнення та армування стиків ГКЛ',
                             'Проклеювання склополотном примикань і кутів',
                             'Поклейка склополотна')
                ORDER BY name
                """, String.class))
                .containsExactly("Заповнення та армування стиків ГКЛ LINEAR_METER",
                        "Поклейка склополотна M2",
                        "Проклеювання склополотном примикань і кутів LINEAR_METER");
    }

    @Test
    void everyDrywallPositionIsFiledUnderOneOfTheFivePhasesAndCarriesNoBrand() {
        assertThat(db.queryForList(
                "SELECT DISTINCT category FROM catalog_templates WHERE trade = 'DRYWALL' ORDER BY 1",
                String.class))
                .containsExactly("Звукоізоляція та утеплення", "Каркас і обшивка", "Надбавки",
                        "Оздоблення під фарбування", "Підготовка та захист");

        assertThat(db.queryForList(
                "SELECT name FROM catalog_templates WHERE trade = 'DRYWALL' "
                        + "AND (name ILIKE '%walraven%' OR name ILIKE '%tece%')", String.class))
                .as("каталог описує роботу і результат, а не бренд").isEmpty();
    }

    @Test
    void theFinishingStagesStillShip_thoughTheLEVELSAreBundlesNow() {
        // V116 shipped the three levels as three POSITIONS; V121 overturned exactly that — a level
        // is a chain of works, so it is a bundle (DrywallQualityLevelsOnLiveDataIntegrationTest
        // owns them now). What V116 contributed and still stands is the STAGES underneath: the
        // level bundles are assembled from them, and a master who prices the chain himself bills
        // them one by one.
        assertThat(db.queryForList("""
                SELECT name || ' | ' || suggested_price FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name IN (
                    'Криючий ґрунт-наповнювач', 'Локальне дефектування',
                    'Мікрошліфування дефектів', 'Вологе обезпилювання поверхні')
                ORDER BY name
                """, String.class))
                .containsExactly(
                        "Вологе обезпилювання поверхні | 40.00",
                        "Криючий ґрунт-наповнювач | 120.00",
                        "Локальне дефектування | 60.00",
                        "Мікрошліфування дефектів | 60.00");

        assertThat(db.queryForList("""
                SELECT name FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ під фарбування%'
                """, String.class))
                .as("рівень більше не продається однією позицією (V121)").isEmpty();
    }

    @Test
    void fourteenBundlesBecomeThree_plusTheFiveLevelLadderV121Added() {
        assertThat(db.queryForList(
                "SELECT name FROM estimate_templates WHERE is_default AND trade = 'DRYWALL' "
                        + "AND name NOT LIKE 'Підготовка ГКЛ ·%' ORDER BY name", String.class))
                .as("V121 замінив «Підготовка ГКЛ під фарбування» на драбину рівнів")
                .containsExactly("Звукоізоляція та утеплення", "Стеля з гіпсокартону",
                        "Стіни та перегородки з гіпсокартону");

        // V112's rule: a bundle is a SEQUENCE, and a 2-3-line one is not worth reaching for. The
        // level ladder is excluded on purpose — Q1 is genuinely three lines, and there the
        // shortness IS the product (what the master reaches for is the named level and the
        // paragraph the client reads).
        assertThat(db.queryForList("""
                SELECT et.name || ': ' || count(i.id)
                FROM estimate_templates et JOIN estimate_template_items i ON i.template_id = et.id
                WHERE et.is_default AND et.trade = 'DRYWALL' AND et.name NOT LIKE 'Підготовка ГКЛ ·%'
                GROUP BY et.name HAVING count(i.id) < 6
                """, String.class))
                .as("«Короб під ванну» на дві позиції — саме та форма, яку V112 відкинув").isEmpty();
    }

    @Test
    void jointSandingIsSoldSeparatelyAndTheNeighbouringRowStopsClaimingIt() {
        // V117. The master's own matrix lists «Шліфування стиків ГКЛ» as stage 1.2 and ships it in
        // Q4 but NOT in Q3 — a step that is sold or not sold, not a detail of another step. (V121's
        // Q1/Q2 carry it too; V122 retired Q3+, which was the third bundle that named it.)
        assertThat(db.queryForObject("""
                SELECT category || ' | ' || type || ' | ' || unit || ' | ' || suggested_price
                FROM catalog_templates WHERE trade = 'DRYWALL' AND name = 'Шліфування стиків ГКЛ'
                """, String.class))
                .isEqualTo("Оздоблення під фарбування | WORK | LINEAR_METER | 40.00");

        assertThat(db.queryForObject("""
                SELECT description FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name = 'Заповнення та армування стиків ГКЛ'
                """, String.class))
                .as("дві позиції не можуть продавати те саме шліфування")
                .doesNotContain("шліфування");

        // V123. The hint names the levels that actually ship the stage — V122 re-worded it off the
        // retired Q3+ and left out Q1, which lists it right after the joint it sands.
        assertThat(db.queryForObject("""
                SELECT description FROM catalog_templates
                WHERE trade = 'DRYWALL' AND name = 'Шліфування стиків ГКЛ'
                """, String.class))
                .isEqualTo("Шліфування заповненого стику до площини аркуша. "
                        + "Окремий етап у Q1, Q2 і Q4.");
    }

    @Test
    void jointSandingSitsInTheFinishingSequenceRightAfterTheJointItself() {
        // V117's claim, re-read on the V121 bundles after V122 retired Q3+: the sanding follows the
        // joint it sands, whichever way the joint was made. Q1/Q2 bed it on mesh, Q4 on
        // high-density paper tape.
        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id
                WHERE et.is_default AND et.trade = 'DRYWALL'
                  AND et.name = 'Підготовка ГКЛ · Q2 — під шпалери'
                ORDER BY i.sort_order LIMIT 3
                """, String.class))
                .containsExactly("Заповнення та армування стиків ГКЛ", "Шліфування стиків ГКЛ",
                        "Грунтування");

        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id
                WHERE et.is_default AND et.trade = 'DRYWALL'
                  AND et.name = 'Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)'
                ORDER BY i.sort_order LIMIT 3
                """, String.class))
                .containsExactly("Заповнення стиків ГКЛ паперовою стрічкою високої щільності",
                        "Шліфування стиків ГКЛ", "Грунтування");

        // The tier that dropped it is Q3, and that is the whole point of selling it separately.
        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id
                WHERE et.is_default AND et.trade = 'DRYWALL'
                  AND et.name LIKE 'Підготовка ГКЛ · Q3 —%'
                """, String.class))
                .doesNotContain("Шліфування стиків ГКЛ");

        // V122: the ladder the master asked for, and nothing between its rungs.
        assertThat(db.queryForList("""
                SELECT substring(name from 'Q[0-9+]+') FROM estimate_templates
                WHERE is_default AND trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ · Q%'
                ORDER BY name
                """, String.class))
                .containsExactly("Q1", "Q2", "Q3", "Q4");

        // sort_order IS the content (V112). Inserting in the middle means renumbering, and a
        // collision would make the sequence's order arbitrary.
        assertThat(db.queryForList("""
                SELECT et.name FROM estimate_templates et
                JOIN estimate_template_items i ON i.template_id = et.id
                WHERE et.is_default AND et.trade = 'DRYWALL'
                GROUP BY et.id, et.name HAVING count(*) <> count(DISTINCT i.sort_order)
                """, String.class)).isEmpty();
    }

    @Test
    void jointSandingReachesTheMastersV116HadAlreadyStamped() {
        // V117 keeps V116's catalog version 14 (both land in one deploy), so a version-driven
        // refresh would skip these two — they are already stamped 14. It propagates by name.
        assertThat(owns(SOLO, "Шліфування стиків ГКЛ")).isTrue();
        assertThat(owns(BOTH, "Шліфування стиків ГКЛ")).isTrue();
    }

    // =============================================================================================
    // What it does to a catalog that already exists
    // =============================================================================================

    @Test
    void aDrywallOnlyMasterLosesTheRetiredWordingAndTheMasonry() {
        assertThat(owns(SOLO, RETIRED_AT_OUR_PRICE)).isFalse();
        assertThat(owns(SOLO, MASONRY)).isFalse();
    }

    @Test
    void theSameMasonryRowSurvivesForAMasterWhoIsAlsoABuilder() {
        // The whole reason V116's delete guard filters by the master's OWN trades instead of asking
        // catalog_templates globally, as V83/V97 did. He lays block; it is not drywall, but it is
        // still his work.
        assertThat(owns(BOTH, MASONRY)).isTrue();
    }

    @Test
    void aPriceTheMasterChangedIsNotOursToRetire() {
        assertThat(owns(SOLO, RETIRED_REPRICED))
                .as("рядок бібліотечний, але число вже його — 700 замість наших 650").isTrue();
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(SOLO), RETIRED_REPRICED))
                .isEqualByComparingTo("700.00");
    }

    @Test
    void aPositionTheMasterTypedHimselfIsNeverTouchedEvenUnderARetiredName() {
        assertThat(owns(SOLO, RETIRED_BUT_HIS)).isTrue();
        assertThat(db.queryForObject(
                "SELECT source FROM catalog_items WHERE owner_id = ? AND name = ?",
                String.class, UUID.fromString(SOLO), RETIRED_BUT_HIS)).isEqualTo("MANUAL");
    }

    @Test
    void theNewPositionsArriveWithTheirDescriptionAndTheirPhase() {
        // The level positions this used to read are gone (V121), but the stage rows V116 added
        // still reach an existing master, and the description still rides the copy — explaining
        // the work to the client is the master's job, so he has to be given the words.
        assertThat(db.queryForObject("""
                SELECT count(*) FROM catalog_items
                WHERE owner_id = ? AND name = 'Вологе обезпилювання поверхні'
                  AND category = 'Оздоблення під фарбування'
                  AND source = 'LIBRARY' AND description IS NOT NULL
                """, Integer.class, UUID.fromString(SOLO)))
                .as("опис їде з копією — пояснювати роботу клієнту доведеться майстру").isEqualTo(1);

        // The phases reach an existing master too; otherwise he keeps reading his catalog by object
        // while a master who registers tomorrow reads it by phase.
        assertThat(db.queryForList("""
                SELECT DISTINCT category FROM catalog_items
                WHERE trade = 'DRYWALL' AND source = 'LIBRARY' AND category IS NOT NULL
                  AND category NOT IN ('Підготовка та захист', 'Каркас і обшивка',
                                       'Оздоблення під фарбування',
                                       'Звукоізоляція та утеплення', 'Надбавки')
                """, String.class)).isEmpty();
    }

    @Test
    void hisForkedCopyOfOurBundleIsRepointedButHisOwnTemplateIsNot() {
        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id
                WHERE et.owner_id = ? AND et.name = 'Радіусна перегородка'
                """, String.class, UUID.fromString(SOLO)))
                .as("текст форку — наш; рядок з мертвою назвою застосується за 0 ₴")
                .containsExactly(CANON_RADIUS);

        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                JOIN estimate_templates et ON et.id = i.template_id
                WHERE et.owner_id = ? AND et.name = 'Мій шаблон'
                """, String.class, UUID.fromString(SOLO)))
                .as("шаблон, який майстер написав сам, ми не редагуємо — навіть на краще")
                .containsExactly(RETIRED_AT_OUR_PRICE);
    }

    @Test
    void bothMastersAreToldWhatChangedAndAreMarkedSynced() {
        assertThat(db.queryForObject(
                "SELECT count(*) FROM catalog_update_notices WHERE kind = 'COUNT' AND user_id IN (?, ?)",
                Integer.class, UUID.fromString(SOLO), UUID.fromString(BOTH))).isEqualTo(2);

        // V117 lands in the same deploy and tops up the count instead of queueing its own row: two
        // «каталог оновлено» notices from one deploy would claim an update that never happened.
        assertThat(db.queryForList("""
                SELECT positions_added FROM catalog_update_notices
                WHERE kind = 'COUNT' AND user_id IN (?, ?)
                """, Integer.class, UUID.fromString(SOLO), UUID.fromString(BOTH)))
                .as("V117 доклався в рахунок V116; окремим рядком він читався б як «1»")
                .allMatch(n -> n > 1);

        Integer stale = db.queryForObject("""
                SELECT count(*) FROM users u
                WHERE EXISTS (SELECT 1 FROM user_trades t WHERE t.user_id = u.id AND t.trade = 'DRYWALL')
                  AND u.last_synced_catalog_version < (SELECT MAX(added_in_version) FROM catalog_templates)
                """, Integer.class);
        assertThat(stale)
                .as("інакше «Додати нові позиції» запропонує те, що міграція вже поклала").isZero();
    }
}
