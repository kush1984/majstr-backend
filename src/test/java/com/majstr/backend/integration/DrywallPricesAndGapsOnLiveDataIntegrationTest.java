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
 * V120 — the three prices the master settled (850 / 1350 / 100) and the four positions the drywall
 * catalog was missing.
 *
 * <p>Both halves only mean anything on a catalog that already has masters in it, and a normal test
 * run never sees one, so this uses the "second database migrated to the version before the change"
 * pattern of {@link CatalogOrderOnLiveDataIntegrationTest}: migrate to V119, plant the shapes that
 * matter, finish migrating, then look.
 *
 * <p>The shape that specifically needs a live database is the <b>price notice</b>. A migration must
 * never overwrite a master's own price — {@code CatalogTemplateService.acceptUpdateNotice} is the
 * only door, and it refuses to move a number the master has already changed himself. So two masters
 * are planted on the same position: one still carrying ours, one who re-priced it. The first must
 * be offered the change; the second must be left alone entirely, notice included.
 */
class DrywallPricesAndGapsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v120";

    /** Still holds our old numbers on every repriced position — he is the one to notify. */
    private static final String STALE = "dddddddd-0000-0000-0000-000000000001";
    /** Re-priced «Армування стиків ГКЛ» himself, to the very number we are shipping. */
    private static final String OWN_PRICE = "dddddddd-0000-0000-0000-000000000002";
    /** DRYWALL + PLUMBING: already owns the revision hatch under the trade that claimed it first. */
    private static final String PLUMBER = "dddddddd-0000-0000-0000-000000000003";

    private static final String CEILING = "Каркасна звукоізоляція ГКЛ два слоя стелі";
    private static final String WALLS = "Каркасна звукоізоляція ГКЛ два слоя стін";
    private static final String DOORWAY = "Облаштування дверного пройому звуження розширення";
    private static final String JOINTS = "Армування стиків ГКЛ";

    private static final String FLOOR = "Монтаж сухої збірної підлоги з гіпсоволокна";
    private static final String REPAIR = "Ремонт ділянки конструкції з гіпсокартону";
    private static final String HATCH = "Установка люка-ревізії простого";
    private static final String TAPE = "Монтаж ущільнювальної стрічки на профіль";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV119_seedThreeMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("119"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        master(STALE, "stale", "V120A");
        master(OWN_PRICE, "ownprice", "V120B");
        master(PLUMBER, "plumber", "V120C");
        trades(STALE, "DRYWALL", "PAINTER", "BUILDER");
        trades(OWN_PRICE, "PAINTER");
        trades(PLUMBER, "DRYWALL", "PLUMBING");

        item(STALE, CEILING, "Звукоізоляція", "PAINTER", "M2", "80.00", "LIBRARY");
        item(STALE, WALLS, "Звукоізоляція", "PAINTER", "M2", "60.00", "LIBRARY");
        item(STALE, DOORWAY, "Кладка", "BUILDER", "PIECE", "800.00", "LIBRARY");
        item(STALE, JOINTS, "Штукатурка та армування", "PAINTER", "LINEAR_METER", "75.00", "LIBRARY");

        // His own number, and it is the answer to which of the two the position is worth.
        item(OWN_PRICE, JOINTS, "Штукатурка та армування", "PAINTER", "LINEAR_METER", "100.00",
                "LIBRARY");

        // catalog_items has one row per (owner, name, type, unit) — a position two trades ship
        // under identical wording is stored once, under whichever trade claimed it first.
        item(PLUMBER, HATCH, "Сантехприлади", "PLUMBING", "PIECE", "300.00", "LIBRARY");

        // V116/V117/V120 reach production in one deploy, so their COUNT notices are one notice.
        db.execute("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
                VALUES (gen_random_uuid(), '%s', 'COUNT', 5, 0)
                """.formatted(PLUMBER));
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
    }

    private static void trades(String owner, String... trades) {
        for (String trade : trades) {
            db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', '%s')"
                    .formatted(owner, trade));
        }
    }

    private static void item(String owner, String name, String category, String trade, String unit,
                             String price, String source) {
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', '%s', 'WORK', '%s', %s, '%s', '%s', 0)
                """.formatted(owner, name, category, unit, price, trade, source));
    }

    private static BigDecimal templatePrice(String trade, String name) {
        return db.queryForObject(
                "SELECT suggested_price FROM catalog_templates WHERE trade = ? AND name = ?",
                BigDecimal.class, trade, name);
    }

    private static BigDecimal ownPrice(String owner, String name) {
        return db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(owner), name);
    }

    private static List<String> notices(String owner) {
        return db.queryForList("""
                SELECT position_name || ': ' || old_price || ' → ' || new_price
                FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'PRICE_DRIFT' AND dismissed_at IS NULL
                ORDER BY position_name
                """, String.class, UUID.fromString(owner));
    }

    // =============================================================================================
    // PART 1 — the three numbers
    // =============================================================================================

    @Test
    void theLibraryNowCarriesTheNumbersTheMasterSettledOn() {
        // «беремо 850, 1350 і 100» — each of these was one job priced twice, in two trades.
        assertThat(templatePrice("PAINTER", CEILING)).isEqualByComparingTo("850.00");
        assertThat(templatePrice("PAINTER", WALLS)).isEqualByComparingTo("650.00");
        assertThat(templatePrice("BUILDER", DOORWAY)).isEqualByComparingTo("1350.00");
        assertThat(templatePrice("PAINTER", JOINTS)).isEqualByComparingTo("100.00");
    }

    @Test
    void theTradeThatAlreadyHadTheRightNumberIsUntouched() {
        assertThat(templatePrice("DRYWALL", "Каркасна звукоізоляція (ГКЛ в два слоя) стелі"))
                .isEqualByComparingTo("850.00");
        assertThat(templatePrice("DRYWALL", "Заповнення та армування стиків ГКЛ"))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void aMasterStillOnTheOldNumberIsOfferedTheChangeRatherThanGivenIt() {
        assertThat(notices(STALE)).containsExactly(
                JOINTS + ": 75.00 → 100.00",
                CEILING + ": 80.00 → 850.00",
                WALLS + ": 60.00 → 650.00",
                DOORWAY + ": 800.00 → 1350.00");

        // The price itself moves only when he taps «Прийняти» — a migration never edits a number
        // a master owns.
        assertThat(ownPrice(STALE, CEILING)).isEqualByComparingTo("80.00");
        assertThat(ownPrice(STALE, DOORWAY)).isEqualByComparingTo("800.00");
    }

    @Test
    void aMasterWhoAlreadyRepricedItHimselfHearsNothing() {
        assertThat(notices(OWN_PRICE))
                .as("нічого не міняється — його число вже таке, як ми шлемо").isEmpty();
        assertThat(ownPrice(OWN_PRICE, JOINTS)).isEqualByComparingTo("100.00");
    }

    // =============================================================================================
    // PART 2 — the four positions
    // =============================================================================================

    @Test
    void theFourMissingPositionsShipInsideDrywallsPhaseSequence() {
        assertThat(db.queryForList("""
                SELECT category || ' | ' || name FROM catalog_templates
                WHERE trade = 'DRYWALL' AND added_in_version = 15
                """, String.class)).containsExactlyInAnyOrder(
                "Каркас і обшивка | " + FLOOR,
                "Каркас і обшивка | " + HATCH,
                "Звукоізоляція та утеплення | " + TAPE,
                "Каркас і обшивка | " + REPAIR);

        assertThat(db.queryForObject("""
                SELECT count(*) FROM catalog_templates
                WHERE trade = 'DRYWALL' AND added_in_version = 15
                  AND (suggested_price IS NULL OR suggested_price <= 0 OR sort_order = 0)
                """, Integer.class))
                .as("позиція без ціни ставить у кошторис нуль, а без рангу — гуляє по сторінці")
                .isZero();
    }

    @Test
    void everyDrywallMasterGetsThemWithoutADuplicateForWhatHeAlreadyOwns() {
        assertThat(db.queryForList("""
                SELECT name FROM catalog_items WHERE owner_id = ?
                """, String.class, UUID.fromString(PLUMBER)))
                .as("той самий люк під сантехнікою — один рядок, не два")
                .containsExactlyInAnyOrder(FLOOR, REPAIR, TAPE, HATCH);

        assertThat(ownPrice(PLUMBER, HATCH))
                .as("а ціна на нього — його власна").isEqualByComparingTo("300.00");
    }

    @Test
    void oneDeployStaysOneNotice() {
        List<Integer> pending = db.queryForList("""
                SELECT positions_added FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND dismissed_at IS NULL
                """, Integer.class, UUID.fromString(PLUMBER));

        assertThat(pending).as("друга «каталог оновлено» описувала б оновлення, якого не було")
                .containsExactly(5 + 3);
    }

    @Test
    void theTapeOpensTheFramedHalfOfTheSoundproofingSequence() {
        // A bundle is a SEQUENCE: the profile is taped before the frame goes up.
        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_templates t JOIN estimate_template_items i
                  ON i.template_id = t.id
                WHERE t.is_default AND t.trade = 'DRYWALL' AND t.name = 'Звукоізоляція та утеплення'
                ORDER BY i.sort_order
                """, String.class))
                .containsSubsequence(TAPE, "Каркасна звукоізоляція (ГКЛ в два слоя) стін");

        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i
                WHERE i.template_id = (SELECT id FROM estimate_templates
                                       WHERE is_default AND trade = 'DRYWALL'
                                         AND name = 'Звукоізоляція та утеплення')
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates ct
                                  WHERE ct.trade = 'DRYWALL' AND ct.name = i.name
                                    AND ct.type = i.type AND ct.unit = i.unit)
                """, Integer.class))
                .as("рядок шаблона, що не знайшов позиції, застосується по 0 ₴ і мовчки").isZero();
    }

    @Test
    void addingTemplatesDidNotBreakTheRankingV118Established() {
        // Inserting a template REQUIRES re-running V118's ranking — the new rows would otherwise
        // sit on the column DEFAULT 0, which is the defect V118 cleaned up.
        assertThat(db.queryForObject(
                "SELECT count(*) FROM catalog_templates WHERE sort_order = 0", Integer.class))
                .isZero();
        assertThat(db.queryForObject(
                "SELECT count(*) - count(DISTINCT sort_order) FROM catalog_templates", Integer.class))
                .isZero();

        assertThat(db.queryForList("""
                SELECT category FROM catalog_templates WHERE trade = 'DRYWALL'
                GROUP BY category ORDER BY min(sort_order)
                """, String.class))
                .containsExactly("Підготовка та захист", "Каркас і обшивка",
                        "Звукоізоляція та утеплення", "Оздоблення під фарбування", "Надбавки");
    }
}
