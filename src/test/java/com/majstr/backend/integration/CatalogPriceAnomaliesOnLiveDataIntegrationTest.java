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
 * V157 — the trade review's fourteen price anomalies.
 *
 * <p>The price NOTICE only means anything on a catalog that already has masters in it, so this
 * follows {@link DrywallPricesAndGapsOnLiveDataIntegrationTest}: migrate a second database to V156,
 * plant masters, migrate V157, look. One master still carries our old numbers and must be offered
 * the change; one re-priced a position himself and must be left alone, notice included; one already
 * holds a pending notice and must not get a second.</p>
 */
class CatalogPriceAnomaliesOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v157";

    private static final String STALE = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String OWN_PRICE = "eeeeeeee-0000-0000-0000-000000000002";
    private static final String PENDING = "eeeeeeee-0000-0000-0000-000000000003";

    private static final String DOOR = "Захист вхідних дверей картоном";
    private static final String CLAMPS = "Прокладання труб на хомутах (каналізація)";
    private static final String LOADING = "Вантаження сміття в авто";
    private static final String MANUAL_BELT = "Бетонування армопоясу вручну";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV156_seedMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("156"))
                .load().migrate();

        master(STALE, "stale157", "V157A");
        master(OWN_PRICE, "own157", "V157B");
        master(PENDING, "pending157", "V157C");

        item(STALE, DOOR, "PAINTER", "PIECE", "0.00");
        item(STALE, CLAMPS, "PLUMBING", "LINEAR_METER", "25.00");
        item(STALE, LOADING, "DEMOLITION", "M3", "950.00");
        item(STALE, MANUAL_BELT, "BUILDER", "LINEAR_METER", "1800.00");

        item(OWN_PRICE, CLAMPS, "PLUMBING", "LINEAR_METER", "240.00");

        item(PENDING, LOADING, "DEMOLITION", "M3", "950.00");
        db.execute("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    position_name, old_price, new_price)
                VALUES (gen_random_uuid(), '%s', 'PRICE_DRIFT', 0, 0, '%s', 950.00, 700.00)
                """.formatted(PENDING, LOADING));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("157"))
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
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', 'Категорія', 'WORK', '%s', %s, '%s', 'LIBRARY', 0)
                """.formatted(owner, name, unit, price, trade));
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

    @Test
    void theLibraryCarriesTheFourteenNewPrices() {
        assertThat(templatePrice("PAINTER", DOOR)).isEqualByComparingTo("200");
        assertThat(templatePrice("PLUMBING", CLAMPS)).isEqualByComparingTo("220");
        assertThat(templatePrice("PLUMBING", "Заробка штроб (опалення)")).isEqualByComparingTo("100");
        assertThat(templatePrice("PLUMBING", "Прокладання труб на хомутах (опалення)")).isEqualByComparingTo("180");
        assertThat(templatePrice("PLUMBING", "Шумоізоляція стояка каналізації")).isEqualByComparingTo("300");
        assertThat(templatePrice("DRYWALL", "Вирізка отворів в гіпсокартоні")).isEqualByComparingTo("200");
        assertThat(templatePrice("FLOORING", "Чистка підлоги порохотягом підготовка")).isEqualByComparingTo("60");
        assertThat(templatePrice("PAINTER", "Грунтовка поверхні кварцгрунтом")).isEqualByComparingTo("90");
        assertThat(templatePrice("DEMOLITION", "Вирізка прорізу в цегляній стіні")).isEqualByComparingTo("1000");
        assertThat(templatePrice("DEMOLITION", LOADING)).isEqualByComparingTo("200");
        assertThat(templatePrice("PAINTER", "Монтаж 3 Д панелей з підготовкою під фарбування")).isEqualByComparingTo("800");
        assertThat(templatePrice("PAINTER", "Монтаж 3Д панелей")).isEqualByComparingTo("500");
        assertThat(templatePrice("BUILDER", "Бетонування армопоясу з міксера")).isEqualByComparingTo("1100");
        assertThat(templatePrice("BUILDER", MANUAL_BELT)).isEqualByComparingTo("1200");
    }

    @Test
    void theTileButtonKeepsItsPrice() {
        assertThat(templatePrice("TILING", "Виготовлення кнопки з плитки")).isEqualByComparingTo("13316");
    }

    @Test
    void aMasterStillOnOurOldNumbersIsOfferedEveryChange() {
        assertThat(notices(STALE)).containsExactly(
                "Бетонування армопоясу вручну: 1800.00 → 1200.00",
                "Вантаження сміття в авто: 950.00 → 200.00",
                "Захист вхідних дверей картоном: 0.00 → 200.00",
                "Прокладання труб на хомутах (каналізація): 25.00 → 220.00");
    }

    /** A migration never overwrites a master's price, and never nags about one he set himself. */
    @Test
    void aMasterWhoRepricedItHimselfIsLeftAlone() {
        assertThat(notices(OWN_PRICE)).isEmpty();
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(OWN_PRICE), CLAMPS)).isEqualByComparingTo("240");
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(STALE), CLAMPS)).isEqualByComparingTo("25");
    }

    @Test
    void aPendingNoticeIsNotDoubled() {
        assertThat(notices(PENDING)).containsExactly("Вантаження сміття в авто: 950.00 → 700.00");
    }
}
