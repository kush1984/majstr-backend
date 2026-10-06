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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V146 over the forks a master had already written — the half a clean database cannot test.
 *
 * <p>V126 put {@code owner_id} INSIDE {@code ux_material_norm}, so a master's own coefficient sits
 * BESIDE the shipped row it hides, and a migration that rewrites a shipped figure meets two kinds of
 * fork. The rule V146 applies to every one of its coefficient statements (review B-108):</p>
 *
 * <ul>
 *   <li>a fork still carrying the SHIPPED number is a fork of something else — of the package size,
 *       the waste allowance, the row's existence — and it FOLLOWS the correction;</li>
 *   <li>a fork carrying his OWN number is his answer and is left exactly alone;</li>
 *   <li>a MATERIAL move carries the forks unconditionally (review B-35), because a norm is a
 *       (material, coefficient) PAIR and stranding half of it is worse than moving it.</li>
 * </ul>
 *
 * <p>Runs the real migrations against a throwaway database seeded at V145: the shared container's
 * schema is already past V146, so the rows it would have to act on never exist there.</p>
 */
class TradeReviewBatchOneForkMigrationOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v146";
    private static final String OWNER = "dddddddd-0000-0000-0000-000000000001";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV145_seedForks_thenUpgrade() throws SQLException {
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

        // The world as a master's device found it the day before this deploy.
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("145"))
                .load().migrate();

        seedForks();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedForks() {
        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?::uuid, 'v146fork@majstr.test', 'v146fork@majstr.test', 'x', 'Майстер',
                        '+380', 'ФОП', 'V146FORK1', TRUE)
                """, OWNER);

        // Forks that still carry the shipped figure — he changed something else about the row.
        fork("укладання плитки 300х300", "TILE_GROUT", null);
        fork("монтаж гіпсокартону на стіни", "PROFILE_UD", null);
        fork("грунтовка поверхні кварцгрунтом", "PRIMER_QUARTZ", null);
        fork("каркасна звукоізоляція (гкл в два слоя) стін", "MINERAL_WOOL", null);

        // …and forks that carry his OWN figure. The clinker one is BOTH cases at once: his own
        // coefficient on a material that moves anyway.
        fork("укладання плитки 300х600", "TILE_GROUT", new BigDecimal("0.6"));
        fork("каркасна звукоізоляція (гкл в два слоя) стін", "PROFILE_UD", new BigDecimal("3.0"));
        fork("укладання клінкерної підлогової плитки", "TILE_ADHESIVE_C1", new BigDecimal("6.0"));
    }

    /**
     * `saveOwn` COPIES the shipped norm and overwrites only what the master typed, so a fork carries
     * the shipped trade, basis, material and baseline — which is exactly why a migration that moves
     * any of those has to carry it.
     */
    private static void fork(String nameKey, String code, BigDecimal ownQty) {
        int inserted = db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id,
                                           qty_per_unit, basis, default_param, baseline_param,
                                           waste_percent, sort_order)
                SELECT gen_random_uuid(), ?::uuid, n.trade, n.name_key, n.unit, n.material_id,
                       coalesce(?::numeric, n.qty_per_unit), n.basis, n.default_param,
                       n.baseline_param, n.waste_percent, n.sort_order
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, OWNER, ownQty, nameKey, code);
        // The seed is only meaningful if V145 really shipped the row it forks.
        assertThat(inserted).as("a shipped %s norm on «%s» to fork", code, nameKey).isEqualTo(1);
    }

    // ---- a fork still holding the shipped figure follows ----------------------------------------

    /**
     * 0,4 kg/m² was CE 33/40's 10-30 cm answer, sitting on every format including a 1,6 × 3,2 m slab.
     * A master whose row still says 0,4 never disagreed with the geometry — he forked the row for
     * some other reason — so the corrected geometry is his too.
     */
    @Test
    void aForkStillHoldingTheShippedGroutFigureFollowsTheCorrection() {
        assertThat(ownQty("укладання плитки 300х300", "TILE_GROUT")).isEqualByComparingTo("0.25");
        assertThat(shippedQty("укладання плитки 300х300", "TILE_GROUT")).isEqualByComparingTo("0.25");
    }

    /**
     * The wall's UD was asked as a PERIMETER, which is the ceiling question — and a perimeter is
     * asked ONCE for the whole estimate, so a flat with three lined walls bought one wall's track.
     * The BASIS is the question, not the coefficient, so a fork that still holds 2,1 moves both.
     */
    @Test
    void aForkStillHoldingTheShippedTrackFigureIsAskedTheNewQuestion() {
        Map<String, Object> mine = own("монтаж гіпсокартону на стіни", "PROFILE_UD");
        assertThat(mine.get("basis")).isEqualTo("QUANTITY");
        assertThat((BigDecimal) mine.get("qty_per_unit")).isEqualByComparingTo("0.7");
        assertThat(mine.get("default_param")).as("a quantity basis asks nothing").isNull();
    }

    // ---- a fork holding his own figure is left alone --------------------------------------------

    /** His 0,6 is an answer about HIS joints and HIS grout; the migration has nothing to say to it. */
    @Test
    void aForkHoldingHisOwnGroutFigureKeepsIt() {
        assertThat(ownQty("укладання плитки 300х600", "TILE_GROUT")).isEqualByComparingTo("0.6");
        assertThat(shippedQty("укладання плитки 300х600", "TILE_GROUT"))
                .as("while the shipped row beside it still moves").isEqualByComparingTo("0.2");
    }

    /**
     * And his own figure keeps the QUESTION it was answering. 3,0 lm per metre of perimeter is only
     * meaningful as a perimeter figure — re-filing the basis under it would leave him a number that
     * now means something he never said. The pair moves together or not at all.
     */
    @Test
    void aForkHoldingHisOwnTrackFigureKeepsTheBasisItAnswered() {
        Map<String, Object> mine = own("каркасна звукоізоляція (гкл в два слоя) стін", "PROFILE_UD");
        assertThat((BigDecimal) mine.get("qty_per_unit")).isEqualByComparingTo("3.0");
        assertThat(mine.get("basis")).isEqualTo("PERIMETER");
    }

    // ---- a material move carries the fork unconditionally ---------------------------------------

    /**
     * Clinker floor tile lays on C2, not C1 — and the fork moves WITH it even though the master had
     * typed his own 6,0. A norm is a (material, coefficient) pair: leaving his coefficient on C1
     * would be a row for a product this work is not done with, and the calculator would send him to
     * buy the wrong bag.
     */
    @Test
    void aMaterialMoveCarriesTheForkAndLeavesHisCoefficientOnIt() {
        assertThat(ownMaterial("укладання клінкерної підлогової плитки", "TILE_ADHESIVE%"))
                .isEqualTo("TILE_ADHESIVE_C2");
        assertThat(ownQty("укладання клінкерної підлогової плитки", "TILE_ADHESIVE_C2"))
                .as("his 6,0 is still his").isEqualByComparingTo("6.0");
        assertThat(shippedQty("укладання клінкерної підлогової плитки", "TILE_ADHESIVE_C2"))
                .isEqualByComparingTo("7.0");
    }

    // ---- a re-filing carries the fork, or the pair stops pairing ---------------------------------

    /**
     * The failure B-35 is named after: once the shipped row moves to {@code trade = NULL} and the
     * fork does not, a PAINTER line matches both (the primer is bought twice), a BUILDER line
     * matches only the shipped one (his correction ignored), and {@code MaterialNormService.own()}
     * cannot find the fork from the default any more — so «restore default» silently does nothing
     * and his next edit tries to insert a SECOND fork onto the unique index.
     */
    @Test
    void aSharedPositionsForkIsRefiledWithTheDefaultItHides() {
        assertThat(db.queryForList("""
                SELECT owner_id, trade FROM material_norm
                 WHERE name_key = 'грунтовка поверхні кварцгрунтом'
                   AND (owner_id IS NULL OR owner_id = ?::uuid)
                """, OWNER)).hasSize(2).allSatisfy(row ->
                assertThat(row.get("trade")).as("both sides of the pair are trade-less").isNull());
    }

    /**
     * The one DELETE in V146 is shipped-rows-only, and deliberately: a master who forked the wool
     * row on the frame position typed a figure of his own against a SEQUENCE of his own, and taking
     * it away would delete an answer he can still see on his screen.
     */
    @Test
    void aForkOfADeletedShippedRowSurvivesIt() {
        assertThat(ownQty("каркасна звукоізоляція (гкл в два слоя) стін", "MINERAL_WOOL"))
                .isEqualByComparingTo("1.05");
        assertThat(db.queryForObject("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стін'
                """, Integer.class)).as("the shipped row it hid is gone").isZero();
    }

    /**
     * The property all of the above hangs on, asked once for every fork seeded here: the fork and
     * the default it hides still share ONE natural key. That is what {@code preferOwn} collapses on
     * the read path and what {@code own()} looks the fork up by — and a pair that stopped sharing it
     * is a state the read path has to guess between.
     */
    @Test
    void everyForkStillSharesOneNaturalKeyWithTheRowItHides() {
        assertThat(db.queryForList("""
                SELECT mine.name_key, mine.material_id FROM material_norm mine
                 WHERE mine.owner_id = ?::uuid
                   AND NOT EXISTS (SELECT 1 FROM material_norm shipped
                                    WHERE shipped.owner_id IS NULL
                                      AND shipped.trade IS NOT DISTINCT FROM mine.trade
                                      AND shipped.name_key = mine.name_key
                                      AND shipped.unit = mine.unit
                                      AND shipped.material_id IS NOT DISTINCT FROM mine.material_id)
                """, OWNER))
                .as("forks whose default moved out from under them — the wool one is expected, "
                        + "because V146 deleted the shipped row on purpose")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("name_key"))
                        .isEqualTo("каркасна звукоізоляція (гкл в два слоя) стін"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, Object> own(String nameKey, String code) {
        return db.queryForMap("""
                SELECT n.basis, n.qty_per_unit, n.default_param, n.baseline_param, n.trade
                  FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id = ?::uuid AND n.name_key = ? AND m.code = ?
                """, OWNER, nameKey, code);
    }

    private BigDecimal ownQty(String nameKey, String code) {
        return (BigDecimal) own(nameKey, code).get("qty_per_unit");
    }

    private BigDecimal shippedQty(String nameKey, String code) {
        return db.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = ? AND m.code = ?
                """, BigDecimal.class, nameKey, code);
    }

    private String ownMaterial(String nameKey, String codePattern) {
        return db.queryForObject("""
                SELECT m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id = ?::uuid AND n.name_key = ? AND m.code LIKE ?
                """, String.class, OWNER, nameKey, codePattern);
    }
}
