package com.majstr.backend.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V141 (review B-55) gives every act line a {@code line_kind}, and the acts already signed have to
 * keep reading exactly as they were signed — so the backfill must reproduce, row by row, the rule
 * the code used until now: no {@code estimate_item_id} means «an additional work».
 *
 * <p>That rule is only visible on LIVE data: a normal test run migrates an empty schema, where the
 * {@code UPDATE} touches nothing. So this is the second-database drill
 * ({@link PaymentReceiptMigrationOnLiveDataIntegrationTest} established the pattern) — migrate to
 * V140, write an act the way the old code wrote acts, then upgrade.</p>
 *
 * <p>Getting it wrong is not cosmetic: a linked line mislabelled ADDITIONAL would be rolled into a
 * SIGNED ADDENDUM by {@code ActAddendumCreator} the next time anything re-signed, billing work the
 * estimate already contains a second time; an ADDITIONAL line mislabelled ESTIMATE would drop out
 * of the ADDENDUM and out of «За договором» while the act still bills it.</p>
 */
class WorkActLineKindMigrationOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v141";
    private static final String OWNER = "dddddddd-0000-0000-0000-000000000001";
    private static final String PROJECT = "dddddddd-0000-0000-0000-000000000002";
    private static final String ESTIMATE = "dddddddd-0000-0000-0000-000000000003";
    private static final String ESTIMATE_ITEM = "dddddddd-0000-0000-0000-000000000004";
    private static final String ACT = "dddddddd-0000-0000-0000-000000000005";
    /** Closes a position of the estimate — carries both ids. */
    private static final String LINKED_LINE = "dddddddd-0000-0000-0000-000000000006";
    /** Off-estimate work the client accepted on the act itself — carries neither. */
    private static final String ADDITIONAL_LINE = "dddddddd-0000-0000-0000-000000000007";
    /** A line whose estimate line was deleted afterwards: ON DELETE SET NULL left it id-less, and
     *  the old code has read it as an additional work ever since. The migration must not invent a
     *  different answer for it — the signed paper says what it says. */
    private static final String ORPHANED_LINE = "dddddddd-0000-0000-0000-000000000008";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV140_seedAnAct_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("140"))
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
                VALUES ('%s', 'kind@test.ua', 'kind@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'KIND1')
                """.formatted(OWNER));
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, OWNER));
        db.execute("""
                INSERT INTO estimates (id, project_id, status)
                VALUES ('%s', '%s', 'SIGNED')
                """.formatted(ESTIMATE, PROJECT));
        db.execute("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total)
                VALUES ('%s', '%s', 'WORK', 'Шпаклювання', 'M2', 100, 145, 14500)
                """.formatted(ESTIMATE_ITEM, ESTIMATE));
        db.execute("""
                INSERT INTO work_act (id, user_id, project_id, number, kind, status, issued_at,
                                      period_from, period_to, show_materials, show_cumulative,
                                      signed_offline, version, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '7', 'INTERIM', 'SIGNED', now(), now(), now(),
                        true, false, true, 0, now(), now())
                """.formatted(ACT, OWNER, PROJECT));
        line(LINKED_LINE, "'" + ESTIMATE_ITEM + "'", "'" + ESTIMATE + "'", "Шпаклювання", 0);
        line(ADDITIONAL_LINE, "NULL", "NULL", "Демонтаж перегородки", 1);
        line(ORPHANED_LINE, "NULL", "'" + ESTIMATE + "'", "Стеля", 2);
    }

    private static void line(String id, String estimateItemId, String estimateId, String name, int sort) {
        db.execute("""
                INSERT INTO work_act_item (id, work_act_id, estimate_item_id, estimate_id, type, name,
                                           unit, unit_price, quantity, line_total, cumulative_before,
                                           sort_order)
                VALUES ('%s', '%s', %s, %s, 'WORK', '%s', 'M2', 145, 10, 1450, 0, %d)
                """.formatted(id, ACT, estimateItemId, estimateId, name, sort));
    }

    @Test
    void aLineThatClosesAnEstimatePositionStaysAnEstimateLine() {
        assertThat(kindOf(LINKED_LINE)).isEqualTo("ESTIMATE");
    }

    @Test
    void aLineWithNoEstimatePositionBecomesAdditional() {
        assertThat(kindOf(ADDITIONAL_LINE)).isEqualTo("ADDITIONAL");
        // Including one that merely LOST its position: the act was signed as an additional work, and
        // nothing about the estimate being tidied away later changes what the client signed.
        assertThat(kindOf(ORPHANED_LINE)).isEqualTo("ADDITIONAL");
    }

    @Test
    void theColumnIsMandatoryAndClosed() {
        String def = db.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_name = 'work_act_item' AND column_name = 'line_kind'
                """, String.class);
        assertThat(def).isNotNull().contains("ESTIMATE");

        // A fourth value would be a line nothing downstream knows how to bill — the ADDENDUM, the
        // PDF's section split and the «Прийнято актами» sum each switch on exactly these three.
        assertThatThrownBy(() -> db.update("""
                UPDATE work_act_item SET line_kind = 'SOMETHING_ELSE' WHERE id = ?
                """, UUID.fromString(LINKED_LINE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String kindOf(String lineId) {
        return db.queryForObject("SELECT line_kind FROM work_act_item WHERE id = ?",
                String.class, UUID.fromString(lineId));
    }
}
