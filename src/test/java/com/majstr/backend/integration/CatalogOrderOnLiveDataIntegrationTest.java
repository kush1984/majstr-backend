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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V118 — the catalog gets an order, and DRYWALL's first phase gets the master's own wording.
 *
 * <p>Both halves only exist on a catalog that already has rows in it, and a normal test run never
 * sees one (the shared database is migrated before anything is written), so this uses the
 * "second database migrated to the version before the change" pattern established by
 * {@link TilingCatalogRebuildOnLiveDataIntegrationTest}: migrate to V117, plant the shapes that
 * matter, finish migrating, then look.
 *
 * <p>The shape that specifically needs a live database is the <b>orphan re-home</b>. V116 retired
 * masonry from DRYWALL while BUILDER kept shipping it, so a master who already owned the copy is
 * left holding a row whose stored trade no longer recognizes its name — «Кладка» on the drywall
 * screen, which is exactly what the master was looking at when he asked for this. It may only be
 * moved to a trade he ACTUALLY HAS, so two masters are planted: one who lays block and one who
 * does not.
 */
class CatalogOrderOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v118";

    /** DRYWALL + BUILDER — his orphaned masonry row goes home to BUILDER. */
    private static final String BUILDS = "cccccccc-0000-0000-0000-000000000001";
    /** DRYWALL only — the same row must stay put; moving it would hide a priced position. */
    private static final String DRYWALL_ONLY = "cccccccc-0000-0000-0000-000000000002";

    private static final String MASONRY = "Кладка перегородки з цегли до 50м2";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV117_seedTwoMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("117"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        master(BUILDS, "builds", "ORD1");
        master(DRYWALL_ONLY, "dryonly", "ORD2");
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', 'DRYWALL'), ('%s', 'BUILDER')"
                .formatted(BUILDS, BUILDS));
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', 'DRYWALL')"
                .formatted(DRYWALL_ONLY));

        // The orphan. V116 took these names out of DRYWALL; the stored copy kept the trade it was
        // copied under, so it shows up under a category DRYWALL no longer has.
        item(BUILDS, MASONRY, "Кладка", "DRYWALL", "M2", "900.00", "LIBRARY", 0);
        item(DRYWALL_ONLY, MASONRY, "Кладка", "DRYWALL", "M2", "900.00", "LIBRARY", 0);

        // Everything a master received after V87's one-off backfill landed on the column DEFAULT 0
        // — CatalogTemplateService.missingItems never numbered a copy. A whole registration's worth
        // of catalog sharing one slot is why his page came back in a different order every time.
        item(BUILDS, "Грунтування", "Підготовка", "DRYWALL", "M2", "33.00", "LIBRARY", 0);
        item(BUILDS, "Монтаж гіпсокартону на стелю рівну", "Каркас і обшивка", "DRYWALL", "M2",
                "560.00", "LIBRARY", 0);

        // Typed by the master himself, so no template carries its rank — it has to land inside the
        // folder it names, not after everything he owns.
        item(BUILDS, "Мій власний захист підлоги", "Підготовка та захист", "DRYWALL", "M2",
                "40.00", "MANUAL", 0);
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
    }

    private static void item(String owner, String name, String category, String trade, String unit,
                             String price, String source, int sortOrder) {
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', '%s', 'WORK', '%s', %s, '%s', '%s', %d)
                """.formatted(owner, name, category, unit, price, trade, source, sortOrder));
    }

    private static String storedAs(String owner, String name) {
        return db.queryForObject(
                "SELECT trade || ' | ' || category FROM catalog_items WHERE owner_id = ? AND name = ?",
                String.class, UUID.fromString(owner), name);
    }

    // =============================================================================================
    // PART 1 — the rename
    // =============================================================================================

    @Test
    void drywallsFirstPhaseCarriesTheNameTheMasterUsesForIt() {
        assertThat(db.queryForList(
                "SELECT DISTINCT category FROM catalog_templates WHERE trade = 'DRYWALL' ORDER BY 1",
                String.class))
                .as("«Підготовка, Підготовка та захист, Оздоблення — це впринципі одна категорія»")
                .containsExactly("Звукоізоляція та утеплення", "Каркас і обшивка", "Надбавки",
                        "Оздоблення під фарбування", "Підготовка та захист");

        assertThat(db.queryForObject("""
                SELECT count(*) FROM catalog_items
                WHERE trade = 'DRYWALL' AND source = 'LIBRARY' AND category = 'Підготовка'
                """, Integer.class))
                .as("перейменування має дійти і до вже скопійованих рядків").isZero();
    }

    // =============================================================================================
    // PART 2 — the orphan
    // =============================================================================================

    @Test
    void anOrphanedRowGoesHomeToATradeTheMasterActuallyHas() {
        assertThat(storedAs(BUILDS, MASONRY))
                .as("кладка не гіпсокартон, але вона його робота").isEqualTo("BUILDER | Кладка");
    }

    @Test
    void theSameRowStaysPutForAMasterWhoDoesNotLayBlock() {
        // Nothing he owns can host it, and dropping it into a foreign trade would file a position
        // he priced under a chip he never opens.
        assertThat(storedAs(DRYWALL_ONLY, MASONRY)).isEqualTo("DRYWALL | Кладка");
    }

    // =============================================================================================
    // PART 3/4 — the order
    // =============================================================================================

    @Test
    void theLibraryStatesTheOrderTheWorkIsActuallyDoneIn() {
        List<String> phases = db.queryForList("""
                SELECT category FROM catalog_templates WHERE trade = 'DRYWALL'
                GROUP BY category ORDER BY min(sort_order)
                """, String.class);

        assertThat(phases).as("«перше беремо Підготовку, а потім вже роботи»")
                .containsExactly("Підготовка та захист", "Каркас і обшивка",
                        "Звукоізоляція та утеплення", "Оздоблення під фарбування", "Надбавки");
    }

    @Test
    void everyTemplateCarriesADistinctNonZeroRank() {
        // 0 has to stop meaning "never ordered" — that is the whole defect being fixed.
        assertThat(db.queryForObject(
                "SELECT count(*) FROM catalog_templates WHERE sort_order = 0", Integer.class))
                .isZero();
        assertThat(db.queryForObject(
                "SELECT count(*) - count(DISTINCT sort_order) FROM catalog_templates", Integer.class))
                .isZero();
    }

    @Test
    void aTradeIsOneRunOfRowsRatherThanBeingInterleavedWithTheOthers() {
        // A master running six trades reads his catalog trade by trade; a global rank that sorted
        // by category first would shuffle plumbing prep in among drywall prep. What must hold is
        // that no two trades OVERLAP — not that a trade's ranks are gapless. A retirement (V121
        // dropped three DRYWALL rows) leaves a hole, and a hole interleaves nothing.
        assertThat(db.queryForObject("""
                SELECT count(*) FROM (
                    SELECT trade, min(sort_order) AS lo, max(sort_order) AS hi
                    FROM catalog_templates GROUP BY trade) a
                JOIN (
                    SELECT trade, min(sort_order) AS lo, max(sort_order) AS hi
                    FROM catalog_templates GROUP BY trade) b
                  ON a.trade < b.trade AND a.lo <= b.hi AND b.lo <= a.hi
                """, Integer.class)).isZero();
    }

    @Test
    void aMastersOwnCatalogIsRenumberedFromIt() {
        List<String> names = db.queryForList("""
                SELECT name FROM catalog_items WHERE owner_id = ? ORDER BY sort_order
                """, String.class, UUID.fromString(BUILDS));

        assertThat(names).as("бібліотечний рядок бере ранг свого шаблона")
                .containsSubsequence("Грунтування", "Монтаж гіпсокартону на стелю рівну");
        assertThat(names).as("позиція, яку майстер написав сам, лишається всередині своєї теки")
                .containsSubsequence("Мій власний захист підлоги", "Монтаж гіпсокартону на стелю рівну");
    }

    @Test
    void noTwoPositionsOfOneMasterClaimTheSameSlot() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM (
                    SELECT owner_id FROM catalog_items
                    GROUP BY owner_id, sort_order HAVING count(*) > 1) d
                """, Integer.class))
                .as("однакові sort_order віддають список у довільному порядку").isZero();

        assertThat(db.queryForObject(
                "SELECT min(sort_order) FROM catalog_items WHERE owner_id = ?",
                Integer.class, UUID.fromString(BUILDS)))
                .as("нумерація починається з нуля, як і в nextSortOrder").isZero();
    }
}
