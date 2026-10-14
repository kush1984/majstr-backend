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
 * V169 — one job, one wording across trades, and a shared position's norms answer for every trade.
 *
 * <p>Migrates a second database to V168, plants a PAINTER-only master who owns the losing wording at
 * his own price, a DRYWALL + PAINTER master who owns both and has forked a coefficient of the
 * survivor under DRYWALL, then applies V169.</p>
 */
class CrossTradeDuplicatesOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v169";

    private static final String PAINTER_ONLY = "a1a1a1a1-0000-0000-0000-000000000691";
    private static final String BOTH = "a1a1a1a1-0000-0000-0000-000000000692";

    private static final String LOSER = "Каркасна звукоізоляція ГКЛ два слоя стін";
    private static final String SURVIVOR = "Каркасна звукоізоляція стін з обшивкою ГКЛ у 2 шари";
    private static final String SURVIVOR_KEY = "каркасна звукоізоляція стін з обшивкою гкл у 2 шари";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV168_seed_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("168")).load().migrate();

        master(PAINTER_ONLY, "painteronly169", "V169A", "PAINTER");
        master(BOTH, "both169", "V169B", "DRYWALL");
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('" + BOTH + "', 'PAINTER')");
        item(PAINTER_ONLY, LOSER, "700.00", "PAINTER");
        item(BOTH, LOSER, "640.00", "PAINTER");
        item(BOTH, SURVIVOR, "680.00", "DRYWALL");

        // BOTH's own sheet coefficient on the survivor, forked while it was still filed under DRYWALL.
        db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit)
                SELECT gen_random_uuid(), ?, 'DRYWALL', ?, 'M2', m.id, 2.2 FROM material m WHERE m.code = 'GKL_SHEET'
                """, UUID.fromString(BOTH), SURVIVOR_KEY);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("169")).load().migrate();
    }

    private static void master(String id, String slug, String code, String trade) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('" + id + "', '" + trade + "')");
    }

    private static void item(String owner, String name, String price, String trade) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), ?, ?, 'Звукоізоляція', 'WORK', 'M2', ?, ?, 'LIBRARY', 0)
                """, UUID.fromString(owner), name, new BigDecimal(price), trade);
    }

    private static List<String> names(String owner) {
        return db.queryForList("SELECT name || ' ' || default_price FROM catalog_items WHERE owner_id = ? ORDER BY name",
                String.class, UUID.fromString(owner));
    }

    private static List<String> library(String name) {
        return db.queryForList("""
                SELECT trade || ' ' || suggested_price FROM catalog_templates
                 WHERE name = ? AND type = 'WORK' ORDER BY trade""", String.class, name);
    }

    /** The loser trade keeps the job, under the survivor's wording, at its own price. */
    @Test
    void theLoserTradesRowIsRenamedInPlaceNotDropped() {
        assertThat(library(LOSER)).isEmpty();
        assertThat(library(SURVIVOR)).containsExactly("DRYWALL 650.00", "PAINTER 650.00");
        assertThat(library("Облицювання фасаду натуральним або штучним каменем"))
                .containsExactly("BUILDER 250.00", "PAINTER 1500.00");
    }

    /** A trade that already ships the survivor just loses its losing row. */
    @Test
    void aTradeShippingBothKeepsOnlyTheSurvivor() {
        assertThat(library("Облицювання клінкерною плиткою")).isEmpty();
        assertThat(library("Облицювання фасаду клінкерною плиткою"))
                .containsExactly("BUILDER 650.00", "PAINTER 250.00");
        assertThat(library("Грунтовка поверхні кварцгрунтом")).isEmpty();
        assertThat(library("Грунтування кварцгрунтом")).containsExactly("BUILDER 25.00", "PAINTER 90.00");
    }

    @Test
    void aMasterWithOnlyTheLoserKeepsHisPriceUnderTheSurvivingName() {
        assertThat(names(PAINTER_ONLY)).containsExactly(SURVIVOR + " 700.00");
    }

    @Test
    void aMasterWithBothKeepsBoth() {
        assertThat(names(BOTH)).containsExactly(LOSER + " 640.00", SURVIVOR + " 680.00");
    }

    @Test
    void noDefaultBundleLineIsLeftOnAMergedWording() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND i.name = 'Грунтовка поверхні кварцгрунтом'""", Integer.class)).isZero();
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'BUILDER'
                   AND i.name = 'Грунтування кварцгрунтом'""", Integer.class)).isPositive();
    }

    /** The framed insulation's norms now reach a PAINTER position too — and a master's fork moved with them. */
    @Test
    void theSurvivorsNormsAndForksAreTradeLess() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM material_norm WHERE name_key = ? AND unit = 'M2' AND owner_id IS NULL
                   AND trade IS NULL""", Integer.class, SURVIVOR_KEY)).isEqualTo(8);
        assertThat(db.queryForList("""
                SELECT coalesce(trade, '∅') || ' ' || qty_per_unit FROM material_norm
                 WHERE name_key = ? AND owner_id = ?""", String.class, SURVIVOR_KEY, UUID.fromString(BOTH)))
                .containsExactly("∅ 2.2000");
    }

    /** A survivor with no norm of its own took the loser's, trade-less. */
    @Test
    void aSurvivorWithoutNormsTakesTheLosers() {
        assertThat(db.queryForList("""
                SELECT coalesce(n.trade, '∅') || ' ' || m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.name_key = 'розділення та розмітка двох кольорів декор штукатурки'""",
                String.class)).containsExactly("∅ TAPE_MASKING");
    }

    /** The three DRYWALL «consumes nothing» verdicts PAINTER ships too now answer for PAINTER. */
    @Test
    void everySharedPositionsNormIsTradeLess() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM material_norm n
                 WHERE n.trade IS NOT NULL AND (n.name_key, n.unit) IN (
                       SELECT lower(trim(name)), unit FROM catalog_templates WHERE type = 'WORK'
                        GROUP BY 1, 2 HAVING count(DISTINCT trade) > 1)""", Integer.class)).isZero();
        assertThat(db.queryForObject("""
                SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND trade IS NULL
                   AND name_key IN ('вологе обезпилювання поверхні', 'локальне дефектування', 'мікрошліфування дефектів')""",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void theLosersKeysAreRecordedSoTheirNormsAreKeptOnPurpose() {
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_renamed_position WHERE renamed_in = 'V169'",
                Integer.class)).isEqualTo(11);
    }
}
