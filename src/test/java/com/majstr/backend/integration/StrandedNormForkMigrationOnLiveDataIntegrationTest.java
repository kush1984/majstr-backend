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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V143 over the data V137 left behind (review B-35): a master's own coefficient fork, seeded while
 * the shipped norm still said DRYWALL, then carried across the migration that moved the shipped one
 * to {@code trade = NULL}.
 *
 * <p>The damage was not a tidiness problem. V126 put {@code owner_id} INSIDE
 * {@code ux_material_norm} so a fork sits beside the default it hides, and the read path pairs the
 * two on the natural key — trade included. Once the default moved and the fork did not, a DRYWALL
 * line matched BOTH rows (the primer was bought twice), a PAINTER line matched only the shipped one
 * (his correction ignored), and {@code MaterialNormService.own()} could not find the fork from the
 * default any more, so «restore default» silently did nothing and the next edit tried to insert a
 * second fork onto the unique index.</p>
 *
 * <p>Runs the real migrations against a throwaway database, because the point is what V143 does to
 * rows that already exist — the shared container's schema is already past it.</p>
 */
class StrandedNormForkMigrationOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v143";
    private static final String OWNER = "eeeeeeee-0000-0000-0000-000000000001";
    /** One of the ten names V137 re-filed — the primer, which is the one that got bought twice. */
    private static final String NAME_KEY = "грунтування";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV142_seedAFork_thenUpgrade() throws SQLException {
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

        // Everything up to and including V142 — i.e. the world as a master's device found it, with
        // V137 already having moved the shipped norms and left his own behind.
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("142"))
                .load().migrate();

        seedStrandedFork();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedStrandedFork() {
        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?::uuid, 'fork@majstr.test', 'fork@majstr.test', 'x', 'Майстер', '+380',
                        'ФОП', 'FORKTEST1', TRUE)
                """, OWNER);
        // His fork of the shipped primer norm, copied BEFORE V137 — hence trade = 'DRYWALL', the
        // trade the shipped row carried at the time. `saveOwn` copies everything but the coefficient.
        db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id,
                                           qty_per_unit, waste_percent)
                SELECT gen_random_uuid(), ?::uuid, 'DRYWALL', name_key, unit, material_id,
                       qty_per_unit * 2, waste_percent
                  FROM material_norm
                 WHERE owner_id IS NULL AND name_key = ? AND trade IS NULL
                 LIMIT 1
                """, OWNER, NAME_KEY);
    }

    @Test
    void theForkIsRefiledWithTheDefaultItHides() {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT owner_id, trade, qty_per_unit FROM material_norm
                 WHERE name_key = ? AND (owner_id IS NULL OR owner_id = ?::uuid)
                """, NAME_KEY, OWNER);

        // The seed only makes sense if V137 really shipped this name trade-less and the fork landed.
        assertThat(rows).as("the shipped primer norm and the master's fork of it").hasSize(2);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.get("trade")).as("both sides of the pair are trade-less").isNull());
        assertThat(rows).anySatisfy(row -> assertThat(row.get("owner_id")).isNotNull());
        assertThat(rows).anySatisfy(row -> assertThat(row.get("owner_id")).isNull());
    }

    @Test
    void theTwoRowsNowShareOneNaturalKey_soTheReadPathCanPairThem() {
        // This is the property everything else hangs on: same (trade, name_key, unit, material_id),
        // differing only in owner_id — which is exactly what `preferOwn` collapses, and what
        // `MaterialNormService.own()` looks the fork up by.
        Integer distinctKeys = db.queryForObject("""
                SELECT count(*) FROM (
                    SELECT DISTINCT trade, name_key, unit, material_id FROM material_norm
                     WHERE name_key = ? AND (owner_id IS NULL OR owner_id = ?::uuid)
                ) k
                """, Integer.class, NAME_KEY, OWNER);
        assertThat(distinctKeys).isEqualTo(1);
    }

    @Test
    void noOwnedForkOfTheTenNamesIsStillFiledUnderDrywall() {
        Integer stranded = db.queryForObject("""
                SELECT count(*) FROM material_norm
                 WHERE owner_id IS NOT NULL AND trade = 'DRYWALL'
                   AND name_key IN ('базове шпаклювання під скловолокно',
                                    'герметизація швів стиків герметиком',
                                    'грунтування',
                                    'захист підлоги картоном',
                                    'звукоізоляція стін мінеральною ватою',
                                    'обезпилення поверхні',
                                    'поклейка склополотна',
                                    'шліфування під скловолокно/склохолст',
                                    'шліфування стін/стель (фінішне)',
                                    'шпаклювання фінішне (2–4 рази)')
                """, Integer.class);
        assertThat(stranded).isZero();
    }

}
