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
 * V88 collapses internal whitespace in catalog names so the bundle→catalog name link stays aligned.
 * catalog_items is a MASTER'S OWN data, and the unique index ux_catalog_items_owner_name_type_unit
 * keys on {@code lower(TRIM(name))} — TRIM strips only the ENDS, it does not collapse internal runs.
 * So a master can legitimately hold «Монтаж  решітки» (double space) beside «Монтаж решітки»: two
 * distinct keys. Collapsing both blindly merged their keys and violated the index — a real production
 * deploy failure. This pins the collision-safe rewrite: the migration completes, and it never merges
 * or loses a row.
 *
 * <p>{@link FlywayMigrationsIntegrationTest} migrates an EMPTY database, so it has no master data for
 * V88 to collide on and cannot see this class of bug. Same shape as the other *OnLiveData* tests: a
 * second database migrated to <b>V87</b> (the last version before V88), seeded with the exact
 * whitespace collisions, then migrated to head.</p>
 */
class PercentBaseWhitespaceOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v88";
    private static final String OWNER = "92222222-2222-2222-2222-222222222222";

    // Group A — a canonical row ALREADY exists; the double-space row must be left alone (the exact
    // production case: «монтаж вентиляційної решітки» already present).
    private static final String A_CANON = "Монтаж вентиляційної решітки";
    private static final String A_DUP = "Монтаж  вентиляційної решітки"; // double space

    // Group B — NEITHER row is canonical; exactly one (the lowest id) must be normalised, the other
    // kept as-is, so the two never collapse onto the same key.
    private static final String B_TWO = "Штроблення  стіни під кабель";   // two spaces
    private static final String B_THREE = "Штроблення   стіни під кабель"; // three spaces
    private static final String B_CANON = "Штроблення стіни під кабель";

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV87_seedCollisions_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("87"))
                .load().migrate();

        seedCollisions();
        // The point of the test only holds if the two variants really coexist BEFORE V88 — i.e. the
        // unique index keys on trimmed-not-collapsed names. Proven here, not trusted.
        assertThat(rows("%решітки%")).as("обидва варіанти співіснують до V88").isEqualTo(2);
        assertThat(rows("%кабель%")).isEqualTo(2);

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedCollisions() {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'v88@test.ua', 'v88@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'V88A')
                """.formatted(OWNER));

        insert(A_CANON);
        insert(A_DUP);
        insert(B_TWO);
        insert(B_THREE);
    }

    private static void insert(String name) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, source)
                VALUES (?, ?::uuid, ?, 'WORK', 'PIECE', 100, 'MANUAL')
                """, UUID.randomUUID(), OWNER, name);
    }

    private static int rows(String like) {
        Integer n = db.queryForObject(
                "SELECT count(*) FROM catalog_items WHERE owner_id = ?::uuid AND name LIKE ?",
                Integer.class, OWNER, like);
        assertThat(n).isNotNull();
        return n;
    }

    private static int exact(String name) {
        Integer n = db.queryForObject(
                "SELECT count(*) FROM catalog_items WHERE owner_id = ?::uuid AND name = ?",
                Integer.class, OWNER, name);
        assertThat(n).isNotNull();
        return n;
    }

    // =============================================================================================

    @Test
    void theUpgradeCompletes() {
        assertThat(db.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class))
                .as("жодна міграція не має бути позначена як провалена")
                .isEmpty();
        assertThat(db.queryForObject(
                "SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
                .isGreaterThanOrEqualTo(88);
    }

    @Test
    void nothingIsMergedOrLost() {
        // The whole failure mode was two rows collapsing into one (constraint violation → rolled-back
        // deploy). Both groups must still have BOTH rows after the upgrade.
        assertThat(rows("%решітки%")).as("група A: обидва рядки лишаються").isEqualTo(2);
        assertThat(rows("%кабель%")).as("група B: обидва рядки лишаються").isEqualTo(2);
    }

    @Test
    void whenACanonicalRowExists_theDoubleSpaceRowIsLeftAlone() {
        // The canonical name stays canonical (one row), and the double-space sibling keeps its space
        // rather than being normalised onto the same key.
        assertThat(exact(A_CANON)).as("канонічний рядок лишається рівно один").isOne();
        assertThat(exact(A_DUP)).as("рядок з подвійним пробілом не чіпають — інакше колізія").isOne();
    }

    @Test
    void whenNeitherIsCanonical_exactlyOneIsNormalised() {
        // Exactly one of the two collapses to the canonical spelling; the other keeps its spacing, so
        // their keys stay distinct.
        assertThat(exact(B_CANON)).as("рівно один рядок нормалізовано").isOne();
        int kept = exact(B_TWO) + exact(B_THREE);
        assertThat(kept).as("інший зберігає свій пробіл — сумарно ще один рядок").isOne();
    }
}
