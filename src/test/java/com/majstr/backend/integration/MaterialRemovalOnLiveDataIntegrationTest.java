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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V81 removes materials from the DEFAULT catalog. It is a destructive migration that runs over
 * live master data, so what it must NOT delete is the part worth testing.
 *
 * <p>{@link FlywayMigrationsIntegrationTest} migrates an empty database and therefore cannot see
 * any of this: there are no masters in it, so there is nothing for V81 to spare. And the ordinary
 * Spring slice is no help either — by the time a test method runs, V81 has already executed
 * against an empty schema, so rows inserted afterwards are never offered to it.</p>
 *
 * <p><b>How.</b> Same shape as {@link CatalogCleanupOnLegacyDataIntegrationTest}: a second database
 * in the same container is migrated to <b>V80</b> — the last version before materials go — then
 * given a master whose catalog and templates contain every case the migration has to distinguish,
 * then migrated to the head. So each assertion below describes what V81 does to data that already
 * existed, which is the only situation in which it will ever run.</p>
 *
 * <p>The four "never touched" guarantees written at the top of the migration are the four tests
 * with {@code stays} / {@code survive} in their names. They are the user's constraint, quoted in
 * the migration and pinned here: anything a master added or changed is theirs.</p>
 */
class MaterialRemovalOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v81";
    private static final String OWNER = "44444444-4444-4444-4444-444444444444";
    private static final String OWN_TEMPLATE = "55555555-5555-5555-5555-555555555555";
    private static final String ESTIMATE = "66666666-6666-6666-6666-666666666666";

    /** A material we shipped, left at the price we shipped it with. The one row that must go. */
    private static final String UNTOUCHED_LIBRARY = "Клей для плитки 25 кг";
    /** The same kind of row, but repriced by the master — guarantee (4). */
    private static final String REPRICED_LIBRARY = "Ґрунтовка глибокого проникнення 10 л";
    /** Typed in by the master — guarantee (2). */
    private static final String MANUAL = "Клей на мою власну ціну";
    /** Landed via price-list / receipt import — guarantee (2). */
    private static final String IMPORTED = "Профіль CD 60 (з прайсу)";

    private static JdbcTemplate db;
    private static int defaultMaterialsBefore;

    @BeforeAll
    static void migrateToV80_seedAMaster_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("80"))
                .load().migrate();

        seedAMasterWithEveryCase();
        defaultMaterialsBefore = count("SELECT count(*) FROM catalog_templates WHERE type = 'MATERIAL'");
        assertThat(defaultMaterialsBefore)
                .as("тест не має сенсу, якщо в дефолтному каталозі немає матеріалів до V81")
                .isPositive();
        // Every deletion this test asserts must have something to delete BEFORE the upgrade,
        // otherwise a green run proves nothing. Checked here rather than trusted.
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND ti.type = 'MATERIAL'
                """)).as("матеріальний рядок у дефолтному шаблоні мав засіятись").isPositive();
        assertThat(catalogRows(UNTOUCHED_LIBRARY)).isOne();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedAMasterWithEveryCase() {
        db.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'v81@test.ua', 'v81@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'V81A')
                """.formatted(OWNER));
        db.execute("INSERT INTO user_trades (user_id, trade) VALUES ('%s','TILING')".formatted(OWNER));

        // (a) the library copy, exactly as CatalogTemplateService.copyMissing would have written it:
        //     our name, our unit, OUR price, source = LIBRARY.
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', '%s', 'MATERIAL', 'PIECE', 380, 'МАТЕРІАЛИ',
                        'TILING', 'LIBRARY')
                """.formatted(OWNER, UNTOUCHED_LIBRARY));
        db.execute("""
                INSERT INTO catalog_templates (id, name, type, unit, suggested_price, category, trade)
                VALUES (gen_random_uuid(), '%s', 'MATERIAL', 'PIECE', 380, 'МАТЕРІАЛИ', 'TILING')
                """.formatted(UNTOUCHED_LIBRARY));

        // (b) the same, but the master moved the price off ours — this row is now partly their work
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', '%s', 'MATERIAL', 'PIECE', 512, 'МАТЕРІАЛИ',
                        'TILING', 'LIBRARY')
                """.formatted(OWNER, REPRICED_LIBRARY));
        db.execute("""
                INSERT INTO catalog_templates (id, name, type, unit, suggested_price, category, trade)
                VALUES (gen_random_uuid(), '%s', 'MATERIAL', 'PIECE', 240, 'МАТЕРІАЛИ', 'TILING')
                """.formatted(REPRICED_LIBRARY));

        // (c) + (d) rows we never gave them
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', '%s', 'MATERIAL', 'PIECE', 450, 'МАТЕРІАЛИ',
                        'TILING', 'MANUAL'),
                       (gen_random_uuid(), '%s', '%s', 'MATERIAL', 'PIECE', 95, 'МАТЕРІАЛИ',
                        'TILING', 'IMPORT')
                """.formatted(OWNER, MANUAL, OWNER, IMPORTED));

        // a LIBRARY *work* — proves the migration keys on type, not on where the row came from
        db.execute("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, category,
                                           trade, source)
                VALUES (gen_random_uuid(), '%s', 'Укладання плитки 600х600', 'WORK', 'M2', 950,
                        'ПЛИТОЧНІ РОБОТИ', 'TILING', 'LIBRARY')
                """.formatted(OWNER));

        // an estimate the master already wrote, with a material line in it — guarantee (1)
        db.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('77777777-7777-7777-7777-777777777777', '%s', 'Обʼєкт', 'вул. 1',
                        'IN_PROGRESS')
                """.formatted(OWNER));
        db.execute("""
                INSERT INTO estimates (id, project_id, status)
                VALUES ('%s', '77777777-7777-7777-7777-777777777777', 'DRAFT')
                """.formatted(ESTIMATE));
        db.execute("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price)
                VALUES (gen_random_uuid(), '%s', 'MATERIAL', '%s', 'PIECE', 12, 380)
                """.formatted(ESTIMATE, UNTOUCHED_LIBRARY));

        // A material line in a DEFAULT template. Seeded rather than assumed: the shipped bundles
        // are almost all works, so an assertion that "no default template item is a MATERIAL" would
        // pass without the migration doing anything. Planting one makes the deletion observable.
        db.execute("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                SELECT gen_random_uuid(), t.id, '%s', 'MATERIAL', 'PIECE', 99
                FROM estimate_templates t WHERE t.is_default ORDER BY t.name LIMIT 1
                """.formatted(UNTOUCHED_LIBRARY));

        // the master's OWN template, holding the same material — guarantee (3)
        db.execute("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES ('%s', '%s', 'Мій шаблон', 'TILING', false)
                """.formatted(OWN_TEMPLATE, OWNER));
        db.execute("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                VALUES (gen_random_uuid(), '%s', '%s', 'MATERIAL', 'PIECE', 0),
                       (gen_random_uuid(), '%s', 'Укладання плитки 600х600', 'WORK', 'M2', 1)
                """.formatted(OWN_TEMPLATE, UNTOUCHED_LIBRARY, OWN_TEMPLATE));
    }

    private static int count(String sql) {
        Integer n = db.queryForObject(sql, Integer.class);
        assertThat(n).isNotNull();
        return n;
    }

    private static int catalogRows(String name) {
        Integer n = db.queryForObject(
                "SELECT count(*) FROM catalog_items WHERE owner_id = ? AND name = ?",
                Integer.class, java.util.UUID.fromString(OWNER), name);
        assertThat(n).isNotNull();
        return n;
    }

    // =============================================================================================

    @Test
    void theUpgradeCompletes() {
        assertThat(db.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class))
                .isEmpty();
        assertThat(count("SELECT max(version::int) FROM flyway_schema_history WHERE success"))
                .isGreaterThanOrEqualTo(81);
    }

    @Test
    void theDefaultCatalogHasNoMaterialsLeft() {
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE type = 'MATERIAL'"))
                .as("усі %d матеріалів мали піти з дефолтного каталогу", defaultMaterialsBefore)
                .isZero();
        assertThat(count("SELECT count(*) FROM catalog_templates WHERE type = 'WORK'"))
                .as("роботи лишаються — прибираємо тільки матеріали")
                .isPositive();
    }

    @Test
    void ourOwnCopyInTheMastersCatalogIsGone() {
        assertThat(catalogRows(UNTOUCHED_LIBRARY))
                .as("рядок, який ми скопіювали і якого майстер не торкався")
                .isZero();
    }

    @Test
    void aRepricedLibraryRowStays() {
        assertThat(catalogRows(REPRICED_LIBRARY))
                .as("ціну міняв майстер — це вже його робота, рядок лишається")
                .isOne();
        assertThat(db.queryForObject(
                "SELECT default_price FROM catalog_items WHERE owner_id = ? AND name = ?",
                java.math.BigDecimal.class, java.util.UUID.fromString(OWNER), REPRICED_LIBRARY))
                .isEqualByComparingTo("512");
    }

    @Test
    void materialsTheMasterAddedThemselvesStay() {
        assertThat(catalogRows(MANUAL)).as("вписаний вручну").isOne();
        assertThat(catalogRows(IMPORTED)).as("завезений імпортом з прайсу").isOne();
    }

    @Test
    void worksAreNotAffected() {
        assertThat(catalogRows("Укладання плитки 600х600"))
                .as("LIBRARY, але WORK — міграція дивиться на тип, а не на походження")
                .isOne();
    }

    @Test
    void everyEstimateLineSurvivesUntouched() {
        assertThat(count("SELECT count(*) FROM estimate_items WHERE estimate_id = '%s'"
                .formatted(ESTIMATE)))
                .as("кошторисні рядки — знімки без FK, їх не чіпає жодна міграція каталогу")
                .isOne();
        assertThat(db.queryForObject(
                "SELECT unit_price FROM estimate_items WHERE estimate_id = ?::uuid",
                java.math.BigDecimal.class, ESTIMATE))
                .isEqualByComparingTo("380");
    }

    @Test
    void aMasterBuiltTemplateKeepsItsMaterialLines() {
        assertThat(count("SELECT count(*) FROM estimate_template_items WHERE template_id = '%s'"
                .formatted(OWN_TEMPLATE)))
                .as("шаблон майстра — його власний, обидва рядки лишаються")
                .isEqualTo(2);
    }

    @Test
    void defaultTemplatesLoseTheirMaterialLines() {
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND ti.type = 'MATERIAL'
                """))
                .as("дефолтний шаблон не має вказувати на позицію, якої вже немає в каталозі")
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM estimate_template_items ti
                JOIN estimate_templates t ON t.id = ti.template_id
                WHERE t.is_default AND ti.type = 'WORK'
                """))
                .as("роботи в дефолтних шаблонах лишаються")
                .isPositive();
    }
}
