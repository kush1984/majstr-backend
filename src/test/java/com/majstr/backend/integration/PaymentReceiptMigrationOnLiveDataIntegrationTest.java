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
 * V100 splits {@code project_payment} into PLAN (kept) and FACT (moved to a new
 * {@code payment_receipt} table). The data migration turns every existing {@code paid_amount > 0}
 * row into one receipt — this is the drill that nothing gets lost: Σ {@code paid_amount} before
 * must equal Σ {@code payment_receipt.amount} after, per the "second database migrated to the
 * version before the change" pattern ({@link TilingCatalogRebuildOnLiveDataIntegrationTest}
 * establishes it) — a normal test run only ever sees an empty schema before any row exists.
 */
class PaymentReceiptMigrationOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v100";
    private static final String OWNER = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PROJECT = "bbbbbbbb-0000-0000-0000-000000000002";
    /** A "Вже отримано" style row from the old model — fully paid at creation. */
    private static final String CLOSED = "bbbbbbbb-0000-0000-0000-000000000003";
    /** A partially-received planned row. */
    private static final String PARTIAL = "bbbbbbbb-0000-0000-0000-000000000004";
    /** A pure plan, nothing received yet — must produce NO receipt. */
    private static final String UNPAID = "bbbbbbbb-0000-0000-0000-000000000005";
    /** Its own row, untouched by the other assertions — JUnit doesn't guarantee method order, and
     *  this is the one test that mutates the shared database (deletes a plan row); reusing CLOSED
     *  here would make the other tests fail or pass depending on run order. */
    private static final String DELETE_TARGET = "bbbbbbbb-0000-0000-0000-000000000006";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV99_seedPayments_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("99"))
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
                VALUES ('%s', 'receipts@test.ua', 'receipts@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'RCP1')
                """.formatted(OWNER));
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, OWNER));
        db.execute("""
                INSERT INTO project_payment (id, project_id, amount, purpose, paid_amount, paid_at, sort_order)
                VALUES ('%s', '%s', 500.00, 'Аванс', 500.00, now(), 0)
                """.formatted(CLOSED, PROJECT));
        db.execute("""
                INSERT INTO project_payment (id, project_id, amount, purpose, paid_amount, paid_at, sort_order)
                VALUES ('%s', '%s', 1000.00, 'Після чорнових', 400.00, now(), 1)
                """.formatted(PARTIAL, PROJECT));
        db.execute("""
                INSERT INTO project_payment (id, project_id, amount, purpose, sort_order)
                VALUES ('%s', '%s', 2000.00, 'Фінал', 2)
                """.formatted(UNPAID, PROJECT));
        db.execute("""
                INSERT INTO project_payment (id, project_id, amount, purpose, paid_amount, paid_at, sort_order)
                VALUES ('%s', '%s', 300.00, 'Матеріали', 300.00, now(), 3)
                """.formatted(DELETE_TARGET, PROJECT));
    }

    // =============================================================================================

    @Test
    void everyPaidRowBecomesExactlyOneReceiptAgainstItsOwnStage() {
        List<BigDecimal> closedAmounts = db.queryForList(
                "SELECT amount FROM payment_receipt WHERE plan_payment_id = ?", BigDecimal.class,
                UUID.fromString(CLOSED));
        List<BigDecimal> partialAmounts = db.queryForList(
                "SELECT amount FROM payment_receipt WHERE plan_payment_id = ?", BigDecimal.class,
                UUID.fromString(PARTIAL));

        assertThat(closedAmounts).hasSize(1);
        assertThat(closedAmounts.get(0)).isEqualByComparingTo("500.00");
        assertThat(partialAmounts).hasSize(1);
        assertThat(partialAmounts.get(0)).isEqualByComparingTo("400.00");
    }

    @Test
    void aRowWithNoPaidAmountProducesNoReceiptAtAll() {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM payment_receipt WHERE plan_payment_id = ?", Integer.class,
                UUID.fromString(UNPAID));

        assertThat(count).isZero();
    }

    @Test
    void sumOfReceiptsForTheObjectEqualsTheSumOfPaidAmountBeforeTheMigration_drillSigmaEqualsSigma() {
        // Planted paid_amount total (500 + 400 + 300); unaffected by whether the delete test has
        // run yet — SET NULL only clears the FK on a receipt, the row (and its amount) stays.
        BigDecimal expected = new BigDecimal("500.00").add(new BigDecimal("400.00")).add(new BigDecimal("300.00"));
        BigDecimal actual = db.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_receipt WHERE project_id = ?",
                BigDecimal.class, UUID.fromString(PROJECT));

        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Test
    void deletingAPlanStageDoesNotDeleteTheReceiptItAlreadyClosed() {
        // The FK is ON DELETE SET NULL, not CASCADE — real received money must survive the plan
        // row being removed; it becomes an unplanned receipt instead of disappearing. Uses its own
        // seeded row (DELETE_TARGET), not CLOSED/PARTIAL — this is the only mutating test in the
        // class and JUnit doesn't guarantee method order, so it must not disturb the others.
        db.execute("DELETE FROM project_payment WHERE id = '%s'".formatted(DELETE_TARGET));

        Integer stillThere = db.queryForObject(
                "SELECT COUNT(*) FROM payment_receipt WHERE amount = 300.00 AND project_id = ?",
                Integer.class, UUID.fromString(PROJECT));
        String planPaymentId = db.queryForObject(
                "SELECT plan_payment_id FROM payment_receipt WHERE amount = 300.00 AND project_id = ?",
                String.class, UUID.fromString(PROJECT));

        assertThat(stillThere).isEqualTo(1);
        assertThat(planPaymentId).isNull();
    }
}
