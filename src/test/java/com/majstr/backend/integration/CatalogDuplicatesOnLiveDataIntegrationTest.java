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
 * V167 — duplicates leave the LIBRARY, and nothing a master owns is lost.
 *
 * <p>Migrates a second database to V166, plants the shapes that can go wrong and applies V167: a
 * master who owns only the losing wording (at his own price), a master who owns both, and a fork of
 * a default bundle that carries the losing line.</p>
 */
class CatalogDuplicatesOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v167";

    private static final String ONLY_LOSER = "a1a1a1a1-0000-0000-0000-000000000001";
    private static final String BOTH = "a1a1a1a1-0000-0000-0000-000000000002";

    private static final String LOSER = "Установка лічильника води простої складності";
    private static final String SURVIVOR = "Установка та підключення лічильника води простої складності";

    private static JdbcTemplate db;
    private static UUID fork;

    @BeforeAll
    static void migrateToV166_seed_thenUpgrade() throws SQLException {
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

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("166")).load().migrate();

        master(ONLY_LOSER, "onlyloser167", "V167A");
        master(BOTH, "both167", "V167B");
        item(ONLY_LOSER, LOSER, "1234.00");
        item(BOTH, LOSER, "900.00");
        item(BOTH, SURVIVOR, "1100.00");

        // A fork (by BOTH) of a default bundle, with the losing line put back into it.
        UUID source = db.queryForObject("""
                SELECT t.id FROM estimate_templates t WHERE t.owner_id IS NULL AND t.is_default
                   AND t.trade = 'PLUMBING' ORDER BY t.name LIMIT 1""", UUID.class);
        fork = UUID.randomUUID();
        db.update("INSERT INTO estimate_templates (id, owner_id, name, trade, is_default) VALUES (?, ?, 'Моя сантехніка', 'PLUMBING', false)",
                fork, UUID.fromString(BOTH));
        db.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (gen_random_uuid(), ?, ?, 'WORK', 'PIECE', 99)""", fork, LOSER);
        db.update("INSERT INTO template_default_override (user_id, template_id, forked_template_id) VALUES (?, ?, ?)",
                UUID.fromString(BOTH), source, fork);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("167")).load().migrate();
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('" + id + "', 'PLUMBING')");
    }

    private static void item(String owner, String name, String price) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), ?, ?, 'Лічильники', 'WORK', 'PIECE', ?, 'PLUMBING', 'LIBRARY', 0)
                """, UUID.fromString(owner), name, new BigDecimal(price));
    }

    private static List<String> names(String owner) {
        return db.queryForList("SELECT name || ' ' || default_price FROM catalog_items WHERE owner_id = ? ORDER BY name",
                String.class, UUID.fromString(owner));
    }

    @Test
    void theLosingWordingLeavesTheLibraryOnly() {
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_templates WHERE name = ?", Integer.class, LOSER))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_templates WHERE name = ?", Integer.class, SURVIVOR))
                .isOne();
    }

    /** The default bundle line moved to the survivor, so his only copy takes that name — at HIS price. */
    @Test
    void aMasterWithOnlyTheLoserKeepsHisPriceUnderTheSurvivingName() {
        assertThat(names(ONLY_LOSER)).containsExactly(SURVIVOR + " 1234.00");
    }

    @Test
    void aMasterWithBothKeepsBoth() {
        assertThat(names(BOTH)).containsExactly(LOSER + " 900.00", SURVIVOR + " 1100.00");
    }

    @Test
    void noDefaultBundleLineAndNoForkLineIsLeftOnTheLoser() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND i.name = ?""", Integer.class, LOSER)).isZero();
        assertThat(db.queryForList("SELECT name FROM estimate_template_items WHERE template_id = ?", String.class, fork))
                .contains(SURVIVOR).doesNotContain(LOSER);
    }

    @Test
    void theLosersKeyIsRecordedSoItsNormsAreKeptOnPurpose() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V167'""", Integer.class))
                .isGreaterThan(90);
    }
}
