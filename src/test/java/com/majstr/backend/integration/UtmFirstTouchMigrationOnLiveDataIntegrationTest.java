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
 * V114 adds the three first-touch UTM columns to a table that already holds every existing master.
 *
 * <p>The "second database migrated to the version before the change" drill
 * ({@link PaymentReceiptMigrationOnLiveDataIntegrationTest} establishes the pattern) — a normal run
 * only ever sees an empty schema, so nothing there would notice a {@code NOT NULL} or a {@code
 * DEFAULT} slipped into the ALTER.</p>
 *
 * <p>What it pins is the decision, not just the DDL: an existing master must come out of the
 * migration with {@code utm_source IS NULL}, because they genuinely arrived with no tags and NULL is
 * the value that says so. A {@code DEFAULT 'DIRECT'} would be the tempting mirror of
 * {@code referral_source} and would silently merge "no tags at all" with "a tag that said direct" —
 * after which no report could ever tell the two apart again.</p>
 */
class UtmFirstTouchMigrationOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v114";
    /** A master who registered long before any UTM capture existed. */
    private static final String LEGACY = "dddddddd-0000-0000-0000-000000000001";
    /** A partner-referred master — the OTHER dimension, which must survive untouched. */
    private static final String PARTNER_REFERRED = "dddddddd-0000-0000-0000-000000000002";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV113_seedUsers_thenUpgrade() throws SQLException {
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + DB;
        db = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("113"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'legacy-utm@test.ua', 'legacy-utm@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'UTM1')
                """.formatted(LEGACY));
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, referral_source)
                VALUES ('%s', 'liga-utm@test.ua', 'liga-utm@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'UTM2', 'LIGA')
                """.formatted(PARTNER_REFERRED));
    }

    // =============================================================================================

    @Test
    void anExistingMasterComesOutWithNoTagsAtAll_notASentinel() {
        List<String> tags = db.queryForList(
                "SELECT utm_source FROM users WHERE id = ?", String.class, UUID.fromString(LEGACY));

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0)).isNull();
    }

    @Test
    void theTwoDimensionsStayIndependent_aPartnerCodeIsNotAChannel() {
        String referral = db.queryForObject(
                "SELECT referral_source FROM users WHERE id = ?", String.class,
                UUID.fromString(PARTNER_REFERRED));
        String utm = db.queryForObject(
                "SELECT utm_source FROM users WHERE id = ?", String.class,
                UUID.fromString(PARTNER_REFERRED));

        // The partner survives; the channel is simply unknown for a master who registered before
        // the capture existed. Backfilling one from the other would invent data.
        assertThat(referral).isEqualTo("LIGA");
        assertThat(utm).isNull();
    }

    @Test
    void theNewColumnsAcceptTagsAndAreNotConstrainedToAKnownSet() {
        // Its own row: JUnit does not guarantee method order and this is the one test here that
        // writes, so tagging LEGACY would make the assertions above pass or fail by run order.
        String tagged = "dddddddd-0000-0000-0000-000000000003";
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, utm_source, utm_medium, utm_campaign)
                VALUES ('%s', 'tagged-utm@test.ua', 'tagged-utm@test.ua', 'x', 'Майстер', '+380', 'ФОП',
                        'UTM3', 'tiktok', 'video', 'серпень-2026')
                """.formatted(tagged));

        assertThat(db.queryForObject("SELECT utm_campaign FROM users WHERE id = ?", String.class,
                UUID.fromString(tagged))).isEqualTo("серпень-2026");
    }
}
