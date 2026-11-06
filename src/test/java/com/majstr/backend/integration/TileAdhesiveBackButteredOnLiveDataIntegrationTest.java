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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V189 over the rows a clean database never has: a master's own adhesive figure, and a library row
 * that moved away from V175's 7,0 before the deploy. Runs the real migrations against a throwaway
 * database seeded at V188, then migrates to V189 only.
 */
class TileAdhesiveBackButteredOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v189";
    private static final String OWNER = "dddddddd-0000-0000-0000-000000000189";
    private static final String C2 = "TILE_ADHESIVE_C2";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV188_seed_thenUpgrade() throws SQLException {
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getFirstMappedPort() + "/" + DB;
        db = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        migrate(url, user, pass, "188");

        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?::uuid, 'v189@majstr.test', 'v189@majstr.test', 'x', 'Майстер', '+380', 'ФОП', 'V189OWN1')
                """, OWNER);
        fork("укладання плитки 600х600", new BigDecimal("7.0"));
        fork("укладання плитки 800х800", new BigDecimal("9.0"));
        // A library row that is no longer where V175 left it.
        db.update("""
                UPDATE material_norm n SET qty_per_unit = 6.5
                  FROM material m
                 WHERE m.id = n.material_id AND m.code = ? AND n.owner_id IS NULL
                   AND n.name_key = 'укладання плитки 1000х1000 мм'
                """, C2);

        migrate(url, user, pass, "189");
    }

    private static void migrate(String url, String user, String pass, String target) {
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load().migrate();
    }

    /** What `saveOwn` writes: a copy of the shipped row carrying the coefficient he typed. */
    private static void fork(String nameKey, BigDecimal ownQty) {
        int inserted = db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id,
                                           qty_per_unit, basis, default_param, baseline_param,
                                           waste_percent, sort_order)
                SELECT gen_random_uuid(), ?::uuid, n.trade, n.name_key, n.unit, n.material_id,
                       ?::numeric, n.basis, n.default_param, n.baseline_param, n.waste_percent, n.sort_order
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, OWNER, ownQty, nameKey, C2);
        assertThat(inserted).as("a shipped C2 norm on «%s» to fork", nameKey).isEqualTo(1);
    }

    @Test
    void theLibraryRowsStillAtSevenMoveToEightAndAHalf() {
        assertThat(shippedQty("укладання плитки 600х600")).isEqualByComparingTo("8.5");
        assertThat(shippedQty("укладання плитки 800х800")).isEqualByComparingTo("8.5");
    }

    /** The key V159 renamed from keeps its rows for old estimate lines; they move with the live one. */
    @Test
    void theOldThousandKeyMovesToo() {
        assertThat(db.queryForList("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = 'укладання плитки 1000х1000' AND m.code = ?
                """, BigDecimal.class, C2))
                .isNotEmpty()
                .allSatisfy(q -> assertThat(q).isEqualByComparingTo("8.5"));
    }

    /** Precondition, not self-check (the V176 lesson): a row that moved since is left as it is. */
    @Test
    void aLibraryRowThatMovedSinceIsLeftAlone() {
        assertThat(shippedQty("укладання плитки 1000х1000 мм")).isEqualByComparingTo("6.5");
    }

    /** `saveOwn` writes only the coefficient, so a fork is a figure he typed: his, at 7,0 or not. */
    @Test
    void aMastersOwnFigureIsNeverRewritten() {
        assertThat(ownQty("укладання плитки 600х600")).isEqualByComparingTo("7.0");
        assertThat(ownQty("укладання плитки 800х800")).isEqualByComparingTo("9.0");
    }

    private BigDecimal shippedQty(String nameKey) {
        return db.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, C2);
    }

    private BigDecimal ownQty(String nameKey) {
        return db.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id = ?::uuid AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, OWNER, nameKey, C2);
    }
}
