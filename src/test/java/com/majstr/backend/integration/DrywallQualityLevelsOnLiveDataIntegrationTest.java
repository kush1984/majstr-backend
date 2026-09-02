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
 * V121 — a finish level is a BUNDLE, not a catalog position.
 *
 * <p>V116 sold Q3/Q3+/Q4 as three catalog rows, each carrying the whole chain of works inside its
 * description. The master's verdict: «це не може бути однією позицією… це має бути шаблоном». So
 * the three rows retire and sequences ship in their place — Q1 and Q2 from the industry's own
 * table, Q3 and Q4 from his matrix.
 *
 * <p><b>V122 is the same iteration's second file</b>: Q3+ was the one judgement call V121 wrote
 * down as such, and the master has since answered it — «в нас має бути Q1, Q2, Q3 and Q4, такого я
 * Q3+ - не треба». Its bundle is deleted and the two position hints that named the tier are
 * re-worded. No catalog position retires with it, so no notice is queued and the version stays 15.
 *
 * <p>Live-data harness, same as {@link DrywallPricesAndGapsOnLiveDataIntegrationTest}: migrate a
 * second database to V120, plant the masters, finish migrating, then look. Three shapes only exist
 * on a database that already has masters in it:
 *
 * <ul>
 *   <li>a master still holding a retired position at OUR price — he loses it and hears about it;</li>
 *   <li>a master who re-priced it himself — the row is his, and it stays;</li>
 *   <li>a master who <b>forked</b> the bundle V116 shipped (V113 copy-on-write). His fork must lose
 *       the three retired lines <em>before</em> the shared default is deleted — the override row
 *       that points at his copy is {@code ON DELETE CASCADE} on the default, so after the delete
 *       there is no way left to find it, and it would keep applying three positions at 0 ₴.</li>
 * </ul>
 */
class DrywallQualityLevelsOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v121";

    /** Holds all three level positions at the prices V116 shipped — nothing of his to protect. */
    private static final String STALE = "eeeeeeee-0000-0000-0000-000000000001";
    /** Priced Q4 himself at 1900 — the row is his answer to what the job is worth. */
    private static final String OWN_PRICE = "eeeeeeee-0000-0000-0000-000000000002";
    /** Edited the V116 bundle, so he owns a FORK of it and the default is hidden for him. */
    private static final String FORKER = "eeeeeeee-0000-0000-0000-000000000003";

    private static final String Q3 = "Підготовка ГКЛ під фарбування · Q3 (економ)";
    private static final String Q3_PLUS = "Підготовка ГКЛ під фарбування · Q3+ (преміум)";
    private static final String Q4 = "Підготовка ГКЛ під фарбування · Q4 (еліт)";
    private static final String OLD_BUNDLE = "Підготовка ГКЛ під фарбування";

    private static final String L_Q1 = "Підготовка ГКЛ · Q1 — під плитку та панелі";
    private static final String L_Q2 = "Підготовка ГКЛ · Q2 — під шпалери";
    private static final String L_Q3 = "Підготовка ГКЛ · Q3 — під матову фарбу (економ)";
    /** Dropped by V122 — kept as a constant so the guard below can name what must be absent. */
    private static final String L_Q3_PLUS = "Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)";
    private static final String L_Q4 = "Підготовка ГКЛ · Q4 — під глянець і бокове світло (еліт)";

    private static UUID forkId;
    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV120_seedThreeMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("120"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        master(STALE, "stale121", "V121A");
        master(OWN_PRICE, "own121", "V121B");
        master(FORKER, "fork121", "V121C");
        trades(STALE, "DRYWALL");
        trades(OWN_PRICE, "DRYWALL");
        trades(FORKER, "DRYWALL");

        item(STALE, Q3, "1100.00");
        item(STALE, Q3_PLUS, "1400.00");
        item(STALE, Q4, "1650.00");
        item(OWN_PRICE, Q4, "1900.00");

        // The fork: his own copy of the V116 bundle, plus the override row that hides the shared
        // default for him and points at the copy.
        UUID shared = db.queryForObject(
                "SELECT id FROM estimate_templates WHERE is_default AND trade = 'DRYWALL' AND name = ?",
                UUID.class, OLD_BUNDLE);
        forkId = UUID.randomUUID();
        db.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES (?, ?, ?, 'DRYWALL', false)
                """, forkId, UUID.fromString(FORKER), OLD_BUNDLE);
        db.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                SELECT gen_random_uuid(), ?, name, type, unit, sort_order
                FROM estimate_template_items WHERE template_id = ?
                """, forkId, shared);
        db.update("""
                INSERT INTO template_default_override (user_id, template_id, forked_template_id)
                VALUES (?, ?, ?)
                """, UUID.fromString(FORKER), shared, forkId);
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 15)
                """.formatted(id, slug, slug, code));
    }

    private static void trades(String owner, String... trades) {
        for (String trade : trades) {
            db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s', '%s')"
                    .formatted(owner, trade));
        }
    }

    private static void item(String owner, String name, String price) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), ?, ?, 'Оздоблення під фарбування', 'WORK', 'M2', ?,
                        'DRYWALL', 'LIBRARY', 500)
                """, UUID.fromString(owner), name, new BigDecimal(price));
    }

    private static List<String> bundleLines(String bundle) {
        return db.queryForList("""
                SELECT i.name FROM estimate_templates t
                JOIN estimate_template_items i ON i.template_id = t.id
                WHERE t.is_default AND t.trade = 'DRYWALL' AND t.name = ?
                ORDER BY i.sort_order
                """, String.class, bundle);
    }

    // =============================================================================================
    // The levels themselves
    // =============================================================================================

    @Test
    void aFinishLevelIsNoLongerSoldAsOnePosition() {
        assertThat(db.queryForList("""
                SELECT name FROM catalog_templates WHERE name LIKE 'Підготовка ГКЛ під фарбування ·%'
                """, String.class))
                .as("«це не може бути однією позицією»")
                .isEmpty();
    }

    @Test
    void fourLevelsShip_becauseQ1AndQ2AreRealContractsToo_andQ3PlusIsGone() {
        // «тобто це не тільки кнауф таке робить, а і інші виробники» — Q1 ends under tile, Q2 under
        // wallpaper, and the master was quoting both by hand. Q3+ was V121's one judgement call and
        // V122 is his answer to it: «такого я Q3+ - не треба».
        assertThat(db.queryForList("""
                SELECT name FROM estimate_templates
                WHERE is_default AND trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ ·%'
                ORDER BY name
                """, String.class))
                .containsExactlyInAnyOrder(L_Q1, L_Q2, L_Q3, L_Q4)
                .doesNotContain(L_Q3_PLUS);

        assertThat(db.queryForList("""
                SELECT name FROM estimate_templates
                WHERE is_default AND trade = 'DRYWALL' AND description IS NULL
                  AND name LIKE 'Підготовка ГКЛ ·%'
                """, String.class))
                .as("рівень без пояснення нічого клієнту не каже")
                .isEmpty();
    }

    @Test
    void theBundleThatSoldThemAsPositionsIsGone() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_templates
                WHERE is_default AND trade = 'DRYWALL' AND name = ?
                """, Integer.class, OLD_BUNDLE)).isZero();
    }

    @Test
    void aLevelIsASequence_andQ4IsQ3PlusTheTwoStagesTheMatrixAddsAboveIt() {
        // With Q3+ gone the ladder's top step carries both of the things his matrix adds over Q3:
        // the joint on high-density paper tape instead of serpyanka, and the joint sanded flush.
        assertThat(bundleLines(L_Q4)).first()
                .isEqualTo("Заповнення стиків ГКЛ паперовою стрічкою високої щільності");
        assertThat(bundleLines(L_Q3)).first()
                .isEqualTo("Заповнення та армування стиків ГКЛ");
        assertThat(bundleLines(L_Q3)).doesNotContain("Шліфування стиків ГКЛ"); // his matrix's Q3
        assertThat(bundleLines(L_Q4)).contains("Шліфування стиків ГКЛ")
                .contains("Вологе обезпилювання поверхні");

        // Q1 is genuinely short — «стики і саморізи, далі плитка». The shortness IS the product.
        assertThat(bundleLines(L_Q1)).hasSize(3);
    }

    @Test
    void noPositionHintPointsAtALevelTheAppNoLongerOffers() {
        // A hint is what the master reads on the board. One naming a retired tier is the kind of
        // stale copy that costs the rest of them their credibility.
        assertThat(db.queryForList("""
                SELECT name FROM catalog_templates
                WHERE trade = 'DRYWALL' AND description LIKE '%Q3+%'
                """, String.class)).isEmpty();
        // The masters already hold copies of those hints — a hint is pushed by value, so fixing
        // only the library would leave every existing catalog reading the old wording.
        assertThat(db.queryForList("""
                SELECT ci.name FROM catalog_items ci
                JOIN catalog_templates ct ON ct.trade = 'DRYWALL'
                                         AND lower(trim(ct.name)) = lower(trim(ci.name))
                                         AND ct.type = ci.type AND ct.unit = ci.unit
                WHERE ci.source = 'LIBRARY' AND ci.description LIKE '%Q3+%'
                """, String.class)).isEmpty();
    }

    @Test
    void noLevelLineWouldApplyAtZero() {
        // A bundle line resolves to a price BY NAME, and a miss is silent — it applies at 0 ₴.
        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_templates t
                JOIN estimate_template_items i ON i.template_id = t.id
                WHERE t.is_default AND t.trade = 'DRYWALL'
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates ct
                                  WHERE ct.trade = 'DRYWALL'
                                    AND lower(trim(ct.name)) = lower(trim(i.name))
                                    AND ct.type = i.type AND ct.unit = i.unit)
                """, String.class)).isEmpty();
    }

    @Test
    void aPositionKeepsAHintAndTheBundleKeepsTheParagraph() {
        // The whole point of the round: the long text had nowhere to live on a line — inline it ran
        // the width of the board («все пливе»), and in the portal it pushed the NAME into «…».
        assertThat(db.queryForObject("""
                SELECT max(length(description)) FROM catalog_templates WHERE trade = 'DRYWALL'
                """, Integer.class))
                .as("позиція лишає підказку, абзац належить шаблону")
                .isLessThanOrEqualTo(200);

        assertThat(db.queryForObject("""
                SELECT min(length(description)) FROM estimate_templates
                WHERE is_default AND trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ ·%'
                """, Integer.class)).isGreaterThan(200);
    }

    @Test
    void theCatalogVersionDoesNotMove_becauseNoPositionWasAdded() {
        // A version bump exists to push NEW rows into masters' catalogs; V121 only deletes.
        assertThat(db.queryForObject(
                "SELECT max(added_in_version) FROM catalog_templates", Integer.class))
                .isEqualTo(15);
    }

    // =============================================================================================
    // The masters who were already holding the retired rows
    // =============================================================================================

    @Test
    void aMasterStillOnOurPriceLosesTheRetiredPositionsAndIsToldSo() {
        assertThat(db.queryForList("""
                SELECT name FROM catalog_items WHERE owner_id = ? AND name LIKE '%· Q%'
                """, String.class, UUID.fromString(STALE))).isEmpty();

        assertThat(db.queryForList("""
                SELECT positions_removed FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND dismissed_at IS NULL
                """, Integer.class, UUID.fromString(STALE)))
                .as("каталог у нього змінився — він має це побачити")
                .containsExactly(3);
    }

    @Test
    void aMasterWhoRepricedItHimselfKeepsHisRow() {
        // The guard is the V83/V97/V116 one: drop our row only while it still carries OUR number.
        assertThat(db.queryForObject("""
                SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?
                """, BigDecimal.class, UUID.fromString(OWN_PRICE), Q4))
                .as("ціна його — позиція його")
                .isEqualByComparingTo("1900.00");

        assertThat(db.queryForList("""
                SELECT positions_removed FROM catalog_update_notices
                WHERE user_id = ? AND kind = 'COUNT' AND dismissed_at IS NULL
                """, Integer.class, UUID.fromString(OWN_PRICE))).isEmpty();
    }

    @Test
    void aForkedBundleLosesTheRetiredLinesButSurvivesAsHisOwn() {
        // template_default_override.template_id is ON DELETE CASCADE, so deleting the shared
        // default takes the row that POINTS at this fork with it. Clean the fork first or it keeps
        // three lines naming positions that no longer exist — and applies each at 0 ₴, silently.
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_templates WHERE id = ?
                """, Integer.class, forkId))
                .as("його копія — його, міграція її не видаляє")
                .isEqualTo(1);

        assertThat(db.queryForList("""
                SELECT name FROM estimate_template_items WHERE template_id = ? AND name LIKE '%· Q%'
                """, String.class, forkId)).isEmpty();

        assertThat(db.queryForList("""
                SELECT i.name FROM estimate_template_items i
                WHERE i.template_id = ?
                  AND NOT EXISTS (SELECT 1 FROM catalog_templates ct
                                  WHERE ct.trade = 'DRYWALL'
                                    AND lower(trim(ct.name)) = lower(trim(i.name))
                                    AND ct.type = i.type AND ct.unit = i.unit)
                """, String.class, forkId))
                .as("жодного рядка, що застосується по 0 ₴")
                .isEmpty();
    }

    @Test
    void deletingTemplatesLeftTheLibrarysRankingIntact() {
        // V118's invariants — no rank 0, no shared rank. Deletions cannot break them, and this is
        // the assertion that says so out loud rather than leaving the next migration to guess.
        assertThat(db.queryForObject(
                "SELECT count(*) FROM catalog_templates WHERE sort_order = 0", Integer.class))
                .isZero();
        assertThat(db.queryForObject(
                "SELECT count(*) - count(DISTINCT sort_order) FROM catalog_templates", Integer.class))
                .isZero();
    }
}
