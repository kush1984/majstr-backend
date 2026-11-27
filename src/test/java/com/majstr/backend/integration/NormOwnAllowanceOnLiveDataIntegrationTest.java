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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V227 on a database that already has norms, a master's fork of a concrete norm among them: every old
 * 0 («use the master's allowance») becomes NULL — his fork too, so his figure means what it meant —
 * and only the library's panel and ready-mix concrete get an allowance of their own.
 */
class NormOwnAllowanceOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v227";
    private static final UUID MASTER = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000227");
    private static final String POUR = "бетонування фундаменту з міксера (приймання, вібрування)";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV226_seed_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("226")).load().migrate();

        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, 'norm227@t.ua', 'norm227@t.ua', 'x', 'Майстер', '+380', 'ФОП', 'V227A', TRUE)
                """, MASTER);
        db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                                           basis, sort_order, waste_percent)
                SELECT gen_random_uuid(), ?, n.trade, n.name_key, n.unit, n.material_id, 1.05, n.basis,
                       n.sort_order, 0
                FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = 'CONCRETE_READY'
                """, MASTER, POUR);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("227")).load().migrate();
    }

    @Test
    void noOldZeroIsLeftAndOnlyTheFenceSaysNone() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.waste_percent = 0
                   AND NOT (m.code IN ('PANEL_3D', 'POST_3D') AND n.owner_id IS NULL)""")).isZero();
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.waste_percent = 0 AND m.code IN ('PANEL_3D', 'POST_3D') AND n.owner_id IS NULL
                   AND n.basis = 'SIDES'""")).isEqualTo(2);
    }

    /** The new question's answer is text, and the table holds exactly one of the two per row. */
    @Test
    void aSidesAnswerIsTextAndEveryOtherAnswerIsStillAFigure() {
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN ('estimate_material_param_text', 'estimate_material_param_figure',
                                   'material_norm_qty_per_run_check')""")).isEqualTo(3);
        assertThat(count("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN ('material_norm_basis_check', 'estimate_material_param_basis')
                   AND pg_get_constraintdef(oid) LIKE '%SIDES%'""")).isEqualTo(2);
    }

    @Test
    void readyMixConcreteIsTheVolumePlacedPlusDstusLoss() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'CONCRETE_READY'""")).isGreaterThanOrEqualTo(16);
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'CONCRETE_READY'
                   AND (n.waste_percent IS DISTINCT FROM 1.5 OR n.qty_per_unit NOT IN (1.0, 0.1, 0.001))""")).isZero();
    }

    @Test
    void theMastersForkKeepsHisFigureAndHisAllowance() {
        var row = db.queryForMap("""
                SELECT n.qty_per_unit, n.waste_percent FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id = ? AND n.name_key = ? AND m.code = 'CONCRETE_READY'""", MASTER, POUR);
        assertThat((BigDecimal) row.get("qty_per_unit")).isEqualByComparingTo("1.05");
        assertThat(row.get("waste_percent")).isNull();
    }

    private int count(String sql) {
        Integer n = db.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
