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
 * V158 — DRYWALL's catalog pass, and the RENAME shape every later trade's pass repeats.
 *
 * <p>A bundle line prices itself off the master's own catalog by name, so a rename that misses one of
 * the rows joined by that name prices the line at 0 ₴ or loses its materials. This migrates a second
 * database to V157, plants the shapes that can go wrong, applies V158 and looks: a copy still at our
 * price, a copy he re-priced (with a pending notice), a copy he typed himself (MANUAL), a fork of a default
 * bundle, and a forked norm.</p>
 */
class DrywallCatalogReviewOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v158";

    private static final String OURS = "f1f1f1f1-0000-0000-0000-000000000001";
    private static final String REPRICED = "f1f1f1f1-0000-0000-0000-000000000002";
    private static final String TYPED = "f1f1f1f1-0000-0000-0000-000000000003";

    private static final String OLD_NICHE = "Монтаж ніші під прихований карниз короб під комунікації";
    private static final String NEW_NICHE = "Монтаж ніші з гіпсокартону під прихований карниз";
    private static final String OLD_WALLS = "Каркасна звукоізоляція (ГКЛ в два слоя) стін";
    private static final String NEW_WALLS = "Каркасна звукоізоляція стін з обшивкою ГКЛ у 2 шари";
    private static final String PARTITION = "Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар";

    private static JdbcTemplate db;
    private static UUID fork;

    @BeforeAll
    static void migrateToV157_seed_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("157")).load().migrate();

        master(OURS, "ours158", "V158A");
        master(REPRICED, "repriced158", "V158B");
        master(TYPED, "typed158", "V158C");
        for (String m : List.of(OURS, REPRICED, TYPED)) {
            db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('" + m + "', 'DRYWALL')");
        }

        item(OURS, OLD_NICHE, "LINEAR_METER", "550.00", "LIBRARY", null);
        item(OURS, OLD_WALLS, "M2", "650.00", "LIBRARY", null);
        item(OURS, PARTITION, "M2", "800.00", "LIBRARY", null);

        item(REPRICED, OLD_NICHE, "LINEAR_METER", "700.00", "LIBRARY", "Моя ніша, мій опис");
        item(REPRICED, PARTITION, "M2", "850.00", "LIBRARY", "Мій опис перегородки");
        db.execute("""
                INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed,
                                                    position_name, old_price, new_price)
                VALUES (gen_random_uuid(), '%s', 'PRICE_DRIFT', 0, 0, '%s', 700.00, 600.00)
                """.formatted(REPRICED, OLD_NICHE));
        // His fork of «Стеля з гіпсокартону», which carries the niche line.
        fork = UUID.randomUUID();
        UUID ceiling = db.queryForObject("""
                SELECT id FROM estimate_templates WHERE owner_id IS NULL AND is_default
                   AND trade = 'DRYWALL' AND name = 'Стеля з гіпсокартону'""", UUID.class);
        db.update("INSERT INTO estimate_templates (id, owner_id, name, trade, is_default) VALUES (?, ?, 'Моя стеля', 'DRYWALL', false)",
                fork, UUID.fromString(REPRICED));
        db.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                SELECT gen_random_uuid(), ?, name, type, unit, sort_order FROM estimate_template_items
                 WHERE template_id = ?""", fork, ceiling);
        db.update("INSERT INTO template_default_override (user_id, template_id, forked_template_id) VALUES (?, ?, ?)",
                UUID.fromString(REPRICED), ceiling, fork);
        // His own coefficient on the old key.
        db.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
                SELECT gen_random_uuid(), ?, n.trade, n.name_key, n.unit, n.material_id, 9, n.basis, n.sort_order
                  FROM material_norm n WHERE n.owner_id IS NULL AND n.name_key = ? LIMIT 1""",
                UUID.fromString(REPRICED), OLD_WALLS.toLowerCase());

        item(TYPED, OLD_NICHE, "LINEAR_METER", "500.00", "MANUAL", null);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("158")).load().migrate();
    }

    private static void master(String id, String slug, String code) {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, last_synced_catalog_version)
                VALUES ('%s', '%s@test.ua', '%s@test.ua', 'x', 'Майстер', '+380', 'ФОП', '%s', 14)
                """.formatted(id, slug, slug, code));
    }

    private static void item(String owner, String name, String unit, String price, String source,
                             String description) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order, description)
                VALUES (gen_random_uuid(), ?, ?, 'Каркас і обшивка', 'WORK', ?, ?, 'DRYWALL', ?, 0, ?)
                """, UUID.fromString(owner), name, unit, new BigDecimal(price), source, description);
    }

    private static List<String> names(String owner) {
        return db.queryForList("SELECT name FROM catalog_items WHERE owner_id = ? ORDER BY name",
                String.class, UUID.fromString(owner));
    }

    private static BigDecimal price(String owner, String name) {
        return db.queryForObject("SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                BigDecimal.class, UUID.fromString(owner), name);
    }

    @Test
    void aCopyOnOurPriceIsRenamed() {
        assertThat(names(OURS)).contains(NEW_NICHE, NEW_WALLS).doesNotContain(OLD_NICHE, OLD_WALLS);
        assertThat(price(OURS, NEW_NICHE)).isEqualByComparingTo("550");
    }

    /** The default bundle line is renamed, so a re-priced copy left behind would price at 0 ₴. */
    @Test
    void aRepricedCopyIsRenamedButKeepsHisPriceAndHisWords() {
        assertThat(names(REPRICED)).contains(NEW_NICHE).doesNotContain(OLD_NICHE);
        assertThat(price(REPRICED, NEW_NICHE)).isEqualByComparingTo("700");
        assertThat(db.queryForObject("SELECT description FROM catalog_items WHERE owner_id = ? AND name = ?",
                String.class, UUID.fromString(REPRICED), NEW_NICHE)).isEqualTo("Моя ніша, мій опис");
        assertThat(db.queryForObject("SELECT description FROM catalog_items WHERE owner_id = ? AND name = ?",
                String.class, UUID.fromString(REPRICED), PARTITION)).isEqualTo("Мій опис перегородки");
        assertThat(db.queryForObject("""
                SELECT position_name FROM catalog_update_notices
                 WHERE user_id = ? AND kind = 'PRICE_DRIFT' AND dismissed_at IS NULL""",
                String.class, UUID.fromString(REPRICED))).isEqualTo(NEW_NICHE);
    }

    @Test
    void aPositionHeTypedHimselfIsHis() {
        assertThat(names(TYPED)).contains(OLD_NICHE).doesNotContain(NEW_NICHE);
    }

    @Test
    void bundlesDefaultAndForkedFollowTheRename() {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
                 WHERE t.owner_id IS NULL AND i.name IN (?, ?)""", Integer.class, OLD_NICHE, OLD_WALLS)).isZero();
        assertThat(db.queryForList("SELECT name FROM estimate_template_items WHERE template_id = ?",
                String.class, fork)).contains(NEW_NICHE).doesNotContain(OLD_NICHE);
    }

    /** The old key keeps its rows (an estimate written yesterday), the new key gets the same set. */
    @Test
    void normsExistOnBothKeysShippedAndForked() {
        String oldKey = OLD_WALLS.toLowerCase();
        String newKey = NEW_WALLS.toLowerCase();
        Integer shippedOld = db.queryForObject(
                "SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?", Integer.class, oldKey);
        assertThat(shippedOld).isPositive();
        assertThat(db.queryForObject(
                "SELECT count(*) FROM material_norm WHERE owner_id IS NULL AND name_key = ?", Integer.class, newKey))
                .isEqualTo(shippedOld);
        assertThat(db.queryForObject(
                "SELECT qty_per_unit FROM material_norm WHERE owner_id = ? AND name_key = ?",
                BigDecimal.class, UUID.fromString(REPRICED), newKey)).isEqualByComparingTo("9");
    }

    @Test
    void newPositionsReachTheMastersAndTheLiningBuysItsFrame() {
        assertThat(names(OURS)).contains(
                "Монтаж облицювання стіни на стійковому профілі CW (без кріплення до стіни)",
                "Монтаж відкосів з гіпсокартону на клей");
        assertThat(db.queryForList("""
                SELECT m.code FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL
                   AND n.name_key = 'монтаж облицювання стіни на стійковому профілі cw (без кріплення до стіни)'
                """, String.class)).containsExactlyInAnyOrder(
                "GKL_SHEET", "PROFILE_CW", "PROFILE_UW", "SCREW_TN25", "DOWEL_NAIL");
    }

    @Test
    void descriptionsLandOnTheLibraryAndOnCopiesStillSayingNothing() {
        assertThat(db.queryForObject("SELECT description FROM catalog_items WHERE owner_id = ? AND name = ?",
                String.class, UUID.fromString(OURS), PARTITION)).startsWith("Площа рахується по обшивці");
        assertThat(db.queryForObject("SELECT description FROM catalog_templates WHERE trade = 'DRYWALL' AND name = ?",
                String.class, PARTITION)).startsWith("Площа рахується по обшивці");
    }
}
