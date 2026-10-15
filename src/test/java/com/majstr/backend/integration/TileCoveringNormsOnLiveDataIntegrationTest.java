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
 * V171 — the tile joins the list and NOTHING already there moves.
 *
 * <p>Migrates a second database to V170, plants a master who corrected his own 300×300 adhesive
 * figure, fingerprints every norm and material row, and applies V171: every old row must come out
 * byte-identical (so no adhesive, grout or primer figure on an existing estimate changes), and every
 * new norm must be a tile.</p>
 */
class TileCoveringNormsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v171";
    private static final UUID MASTER = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000171");

    private static final String NORM_ROWS = """
            SELECT n.id::text || '|' || coalesce(n.owner_id::text, '') || '|' || coalesce(n.trade, '') || '|'
                   || n.name_key || '|' || n.unit || '|' || coalesce(n.material_id::text, '') || '|'
                   || coalesce(n.qty_per_unit::text, '') || '|' || n.waste_percent || '|' || n.sort_order || '|'
                   || n.basis || '|' || coalesce(n.default_param::text, '') || '|' || coalesce(n.baseline_param::text, '')
            FROM material_norm n ORDER BY n.id""";
    private static final String MATERIAL_ROWS = """
            SELECT md5(m::text) FROM material m ORDER BY m.id""";

    private static JdbcTemplate db;
    private static List<String> normsBefore;
    private static List<String> materialsBefore;

    @BeforeAll
    static void migrateToV170_seed_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("170")).load().migrate();

        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, 'tile171@t.ua', 'tile171@t.ua', 'x', 'Майстер', '+380', 'ФОП', 'V171A', TRUE)
                """, MASTER);
        db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                                           basis, sort_order)
                SELECT gen_random_uuid(), ?, n.trade, n.name_key, n.unit, n.material_id, 6.0, n.basis, n.sort_order
                FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND n.name_key = 'укладання плитки 300х300' AND m.code = 'TILE_ADHESIVE_C1'
                """, MASTER);

        normsBefore = db.queryForList(NORM_ROWS, String.class);
        materialsBefore = db.queryForList(MATERIAL_ROWS, String.class);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("171")).load().migrate();
    }

    @Test
    void everyNormAndMaterialThatExistedIsUntouched() {
        assertThat(db.queryForList(NORM_ROWS, String.class)).containsAll(normsBefore);
        assertThat(db.queryForList(MATERIAL_ROWS, String.class)).containsAll(materialsBefore);
    }

    @Test
    void everyNewNormIsTheTileOfALayingPosition() {
        List<String> added = db.queryForList(NORM_ROWS, String.class);
        added.removeAll(normsBefore);
        assertThat(added).hasSize(25);
        assertThat(db.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE m.code LIKE 'CERAMIC\\_TILE\\_%' AND n.owner_id IS NULL AND n.trade = 'TILING'
                  AND n.qty_per_unit = 1 AND n.waste_percent = 0 AND n.basis = 'QUANTITY'
                """, Integer.class)).isEqualTo(25);
    }

    @Test
    void aMastersOwnAdhesiveFigureStillStandsAndHidesNoTile() {
        assertThat(db.queryForList("""
                SELECT m.code || ' ' || n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id = ?""", String.class, MASTER))
                .containsExactly("TILE_ADHESIVE_C1 6.0000");
    }
}
