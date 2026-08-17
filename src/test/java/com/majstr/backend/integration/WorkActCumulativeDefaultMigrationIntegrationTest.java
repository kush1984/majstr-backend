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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V106 turns the «ДОВІДКОВО» cumulative block OFF by default and clears it on every existing act (the
 * old per-line shape was wrong). Because it {@code UPDATE}s live rows, it needs the "second database
 * migrated to the version before the change" drill ({@link PaymentReceiptMigrationOnLiveDataIntegrationTest}
 * establishes the pattern) — a normal run only ever sees an empty schema, so the UPDATE would touch
 * nothing there.
 */
class WorkActCumulativeDefaultMigrationIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v106";
    private static final String OWNER = "cccccccc-0000-0000-0000-000000000001";
    private static final String PROJECT = "cccccccc-0000-0000-0000-000000000002";
    /** An existing act created with the old default show_cumulative = true. */
    private static final String ACT = "cccccccc-0000-0000-0000-000000000003";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV105_seedAct_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("105"))
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
                VALUES ('%s', 'cum@test.ua', 'cum@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'CUM1')
                """.formatted(OWNER));
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, OWNER));
        db.execute("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, show_materials, show_cumulative,
                                      signed_offline, version, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '7', 'INTERIM', 'SIGNED', now(), now(), now(),
                        true, true, false, 0, now(), now())
                """.formatted(ACT, OWNER, PROJECT));
    }

    @Test
    void existingActsAreClearedToShowCumulativeFalse() {
        Boolean showCumulative = db.queryForObject(
                "SELECT show_cumulative FROM work_act WHERE id = ?", Boolean.class, UUID.fromString(ACT));

        assertThat(showCumulative).isFalse();
    }

    @Test
    void theColumnDefaultIsNowFalse() {
        String def = db.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_name = 'work_act' AND column_name = 'show_cumulative'
                """, String.class);

        assertThat(def).isNotNull().containsIgnoringCase("false");
    }
}
