package com.majstr.backend.integration;

import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.CatalogTemplateService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * V176 — the market price review of 2026-10-25.
 *
 * <p>The notices, renames and unit changes only mean anything on a catalog that already has masters
 * in it, so this follows {@link CatalogPriceAnomaliesOnLiveDataIntegrationTest}: migrate a second
 * database to V175, plant masters, migrate V176, look. The «нове з бібліотеки» half runs against the
 * context's own database, already at the head, through the service the button calls.</p>
 */
class MarketPriceReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v176";

    private static final String STALE = "eeeeeeee-0000-0000-0176-000000000001";
    private static final String OWN_PRICE = "eeeeeeee-0000-0000-0176-000000000002";
    private static final String PENDING = "eeeeeeee-0000-0000-0176-000000000003";
    private static final String WRECKER = "eeeeeeee-0000-0000-0176-000000000004";

    private static final String MESH = "Армування кладки сіткою арматурою";
    private static final String PARTITION = "Демонтаж перегородки з гіпсокартону";
    private static final String PRIMER = "Грунтування";
    private static final String SEAL = "Опломбування лічильника води";
    private static final String SEAL_NEW = "Організація опломбування лічильника води";
    private static final String PAINT_LINE = "Відведення лінії фарби довкола вікон/дверей";
    private static final String THRESHOLD_DUP = "Приклеювання стиків в місцях порожків, герметизація силіконом";

    private static JdbcTemplate db;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CatalogTemplateService catalogTemplateService;

    @BeforeAll
    static void migrateToV175_seedMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("175"))
                .load().migrate();

        master(STALE, "stale176", "V176A");
        master(OWN_PRICE, "own176", "V176B");
        master(PENDING, "pending176", "V176C");
        master(WRECKER, "wrecker176", "V176D");

        item(STALE, MESH, "BUILDER", "M2", "50.00");
        item(STALE, PARTITION, "DRYWALL", "M2", "60.00");
        item(STALE, PRIMER, "BUILDER", "M2", "33.00");
        item(STALE, PAINT_LINE, "PAINTER", "M2", "100.00");
        item(STALE, THRESHOLD_DUP, "FLOORING", "PIECE", "500.00");

        item(OWN_PRICE, MESH, "BUILDER", "M2", "80.00");
        item(OWN_PRICE, SEAL, "PLUMBING", "PIECE", "650.00");

        item(PENDING, MESH, "BUILDER", "M2", "50.00");
        db.execute("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    position_name, old_price, new_price)
                VALUES (gen_random_uuid(), '%s', 'PRICE_DRIFT', 0, 0, '%s', 50.00, 70.00)
                """.formatted(PENDING, MESH));

        item(WRECKER, PARTITION, "DEMOLITION", "M2", "60.00");

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("176"))
                .load().migrate();
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
    }

    private static void item(String owner, String name, String trade, String unit, String price) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'Категорія', 'WORK', ?, ?::numeric, ?, 'LIBRARY', 0)
                """, owner, name, unit, price, trade);
    }

    private static BigDecimal templatePrice(String trade, String name) {
        return db.queryForObject(
                "SELECT suggested_price FROM catalog_templates WHERE trade = ? AND name = ?",
                BigDecimal.class, trade, name);
    }

    private static List<String> notices(String owner) {
        return db.queryForList("""
                SELECT position_name || ': ' || old_price || ' → ' || new_price
                  FROM catalog_update_notices
                 WHERE user_id = ? AND kind = 'PRICE_DRIFT' AND dismissed_at IS NULL
                 ORDER BY position_name
                """, String.class, UUID.fromString(owner));
    }

    private static List<String> copies(String owner) {
        return db.queryForList("""
                SELECT name || ' [' || unit || '] ' || default_price FROM catalog_items
                 WHERE owner_id = ? ORDER BY name
                """, String.class, UUID.fromString(owner));
    }

    @Test
    void theLibraryCarriesTheNewPrices_perTradeOnASharedRow() {
        assertThat(templatePrice("BUILDER", MESH)).isEqualByComparingTo("100");
        assertThat(templatePrice("DRYWALL", PARTITION)).isEqualByComparingTo("150");
        assertThat(templatePrice("DEMOLITION", PARTITION)).isEqualByComparingTo("140");
        assertThat(templatePrice("PAINTER", PRIMER)).isEqualByComparingTo("40");
        assertThat(templatePrice("BUILDER", PRIMER)).isEqualByComparingTo("33");
    }

    /** One notice per row, by the trade the row is filed under — never one for another trade's move. */
    @Test
    void aMasterOnOurOldNumbersIsOfferedOnlyHisTradesChanges() {
        assertThat(notices(STALE)).containsExactly(
                MESH + ": 50.00 → 100.00",
                PARTITION + ": 60.00 → 150.00");
        assertThat(notices(WRECKER)).containsExactly(PARTITION + ": 60.00 → 140.00");
    }

    @Test
    void aMasterWhoRepricedItHimselfIsLeftAlone() {
        assertThat(notices(OWN_PRICE)).isEmpty();
        assertThat(copies(OWN_PRICE)).contains(MESH + " [M2] 80.00");
    }

    @Test
    void aPendingNoticeIsNotDoubled() {
        assertThat(notices(PENDING)).containsExactly(MESH + ": 50.00 → 70.00");
    }

    /** V158's rename: his copy follows at HIS price, the bundle line follows, the norm is on both keys. */
    @Test
    void aRenameCarriesTheMastersCopyTheBundleAndTheNorm() {
        assertThat(copies(OWN_PRICE)).contains(SEAL_NEW + " [PIECE] 650.00");
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND i.name = ?""", Integer.class, SEAL_NEW))
                .isPositive();
        assertThat(db.queryForList("""
                SELECT name_key FROM material_norm WHERE owner_id IS NULL AND unit = 'PIECE'
                   AND name_key IN ('опломбування лічильника води', 'організація опломбування лічильника води')
                 ORDER BY name_key""", String.class))
                .containsExactly("опломбування лічильника води", "організація опломбування лічильника води");
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V176'",
                Integer.class)).isEqualTo(13);
    }

    /** A unit change moves the copy and the bundle line; the tape is re-ruled per м.п., not copied. */
    @Test
    void aUnitChangeMovesTheCopyTheBundleAndRerulesTheNorm() {
        assertThat(copies(STALE)).contains(PAINT_LINE + " [LINEAR_METER] 100.00");
        assertThat(db.queryForList("""
                SELECT i.unit FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND i.name = ?""", String.class, PAINT_LINE))
                .isNotEmpty().containsOnly("LINEAR_METER");
        assertThat(db.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.unit = 'LINEAR_METER' AND m.code = 'TAPE_MASKING'
                   AND n.name_key = 'відведення лінії фарби довкола вікон/дверей'""", BigDecimal.class))
                .isEqualByComparingTo("1.0");
    }

    /** A duplicate in another unit leaves the library only. */
    @Test
    void aDuplicateLeavesTheLibraryButNotTheMastersCatalog() {
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_templates WHERE name = ?",
                Integer.class, THRESHOLD_DUP)).isZero();
        assertThat(copies(STALE)).contains(THRESHOLD_DUP + " [PIECE] 500.00");
    }

    @Test
    void newPositionsArriveUnderOneVersionAndArePushedNowhere() {
        int version = db.queryForObject("SELECT max(added_in_version) FROM catalog_templates", Integer.class);
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_templates WHERE added_in_version = ?",
                Integer.class, version)).isEqualTo(67);
        assertThat(db.queryForObject("""
                SELECT count(*) FROM catalog_items ci JOIN catalog_templates ct
                    ON lower(trim(ct.name)) = lower(trim(ci.name)) AND ct.unit = ci.unit
                 WHERE ct.added_in_version = ?""", Integer.class, version)).isZero();
        assertThat(db.queryForObject("SELECT max(last_synced_catalog_version) FROM users", Integer.class))
                .isLessThan(version);
        assertThat(db.queryForList("""
                SELECT name FROM catalog_templates WHERE trade = 'DEMOLITION' AND category = 'Зовнішні роботи'
                 ORDER BY name""", String.class)).hasSize(5);
    }

    /** «Додати нові з довідника» offers a synced DEMOLITION master exactly V176's seven. */
    @Test
    void aSyncedMasterSeesTheNewPositionsAsNewFromTheLibrary() {
        UUID id = UUID.randomUUID();
        int version = jdbc.queryForObject("SELECT max(added_in_version) FROM catalog_templates", Integer.class);
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, ?)
                """, id, id + "@test.ua", id + "@test.ua", "V176" + id.toString().substring(0, 6), version - 1);
        jdbc.update("INSERT INTO user_trades (user_id, trade) VALUES (?, 'DEMOLITION')", id);
        try {
            User user = userRepository.findWithTradesById(id).orElseThrow();
            assertThat(catalogTemplateService.countNewFromCatalog(user)).isEqualTo(7);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }
}
