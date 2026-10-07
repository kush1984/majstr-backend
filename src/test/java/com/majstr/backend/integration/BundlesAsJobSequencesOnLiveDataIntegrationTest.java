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
 * V148 — a default bundle is a JOB, walked in the order it is done.
 *
 * <p>The trade review's batch 3 rewrote every default bundle of all nine trades: the catalog dumps
 * went, the two-to-five-line fragments were folded into fuller sequences, every sequence opens with
 * protection and closes with the debris and the cleanup, and 141 positions were added because a
 * bundle line with no catalog position behind it applies at 0 ₴ silently (V112).</p>
 *
 * <p>Live-data harness: migrate a second database to V147, plant the masters whose history only
 * exists on a database with masters in it, finish migrating, then look. The migration's own
 * {@code DO $$} checks prove the sequences landed; what they cannot prove is what happened to a
 * master's fork, a row he priced himself, or a name that arrives under two of his trades at once.</p>
 */
class BundlesAsJobSequencesOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v148";

    /** Tiles, plumbs and demolishes: «Захист підлоги картоном» is new in all three of his trades. */
    private static final String MULTI = "ffffffff-0000-0000-0000-000000000001";
    /** A painter who already sells «Фасування сміття в мішки» at a price of his own. */
    private static final String OWN_PRICE = "ffffffff-0000-0000-0000-000000000002";
    /** Forked «САНТЕХНІКА», the 104-line dump this migration retires. */
    private static final String DUMP_FORKER = "ffffffff-0000-0000-0000-000000000003";
    /** Forked «Котельня», which becomes «Котельня під ключ». */
    private static final String RENAME_FORKER = "ffffffff-0000-0000-0000-000000000004";

    private static final List<String> TRADES = List.of("DRYWALL", "TILING", "PAINTER", "FLOORING",
            "BUILDER", "DEMOLITION", "ELECTRICAL", "PLUMBING", "METAL");

    private static UUID dumpFork;
    private static UUID boilerRoom;
    private static int versionBefore;
    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV147_seedMasters_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("147"))
                .load().migrate();

        seed();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seed() {
        versionBefore = db.queryForObject("SELECT MAX(added_in_version) FROM catalog_templates",
                Integer.class);
        master(MULTI, "multi148", "V148A");
        master(OWN_PRICE, "own148", "V148B");
        master(DUMP_FORKER, "dump148", "V148C");
        master(RENAME_FORKER, "rename148", "V148D");
        trades(MULTI, "TILING", "PLUMBING", "DEMOLITION");
        trades(OWN_PRICE, "PAINTER");
        trades(DUMP_FORKER, "PLUMBING");
        trades(RENAME_FORKER, "PLUMBING");

        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, category, type, unit, default_price,
                                           trade, source, sort_order)
                VALUES (gen_random_uuid(), ?, 'Фасування сміття в мішки', 'Організаційні послуги',
                        'WORK', 'PIECE', 25.00, 'PAINTER', 'MANUAL', 900)
                """, UUID.fromString(OWN_PRICE));

        dumpFork = fork(DUMP_FORKER, "PLUMBING", "САНТЕХНІКА");
        fork(RENAME_FORKER, "PLUMBING", "Котельня");
        boilerRoom = defaultId("PLUMBING", "Котельня");
    }

    /** His own copy of the default plus the override that hides the default for him (V113). */
    private static UUID fork(String owner, String trade, String bundle) {
        UUID shared = defaultId(trade, bundle);
        UUID copy = UUID.randomUUID();
        db.update("""
                INSERT INTO estimate_templates (id, owner_id, name, trade, is_default)
                VALUES (?, ?, ?, ?, false)
                """, copy, UUID.fromString(owner), bundle, trade);
        db.update("""
                INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
                SELECT gen_random_uuid(), ?, name, type, unit, sort_order
                FROM estimate_template_items WHERE template_id = ?
                """, copy, shared);
        db.update("""
                INSERT INTO template_default_override (user_id, template_id, forked_template_id)
                VALUES (?, ?, ?)
                """, UUID.fromString(owner), shared, copy);
        return copy;
    }

    private static UUID defaultId(String trade, String bundle) {
        return db.queryForObject("""
                SELECT id FROM estimate_templates
                 WHERE owner_id IS NULL AND is_default AND trade = ? AND name = ?
                """, UUID.class, trade, bundle);
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

    private static int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static List<String> lines(String trade, String bundle) {
        return db.queryForList("""
                SELECT i.name FROM estimate_templates t
                  JOIN estimate_template_items i ON i.template_id = t.id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = ? AND t.name = ?
                 ORDER BY i.sort_order
                """, String.class, trade, bundle);
    }

    // =============================================================================================
    // The bundles
    // =============================================================================================

    @Test
    void theCatalogDumpsAreNoLongerDefaults() {
        assertThat(count("""
                SELECT count(*) FROM estimate_templates
                 WHERE owner_id IS NULL AND is_default
                   AND name IN ('ЕЛЕКТРИКА', 'САНТЕХНІКА', 'ПІДЛОГА', 'КЛАДКА', 'УСІ ПЛИТОЧНІ РОБОТИ')
                """)).isZero();
    }

    /** The one failure this migration exists not to cause: a line no catalog position stands
     *  behind applies at 0 ₴ and says nothing. */
    @Test
    void everyDefaultBundleLineResolvesToAPositionOfItsOwnTrade() {
        assertThat(db.queryForList("""
                SELECT t.trade || ' | ' || t.name || ' | ' || i.name
                  FROM estimate_templates t JOIN estimate_template_items i ON i.template_id = t.id
                 WHERE t.owner_id IS NULL AND t.is_default
                   AND NOT EXISTS (SELECT 1 FROM catalog_templates ct
                                    WHERE ct.trade = t.trade
                                      AND lower(trim(ct.name)) = lower(trim(i.name))
                                      AND ct.type = i.type AND ct.unit = i.unit)
                """, String.class)).isEmpty();
    }

    /** «Не роби маленьких шаблонів» — the finish levels are applied ON TOP of a construction
     *  bundle, and they are the one deliberate exception. */
    @Test
    void noDefaultBundleIsAFragment() {
        assertThat(db.queryForList("""
                SELECT t.trade || ' | ' || t.name FROM estimate_templates t
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade IN (%s)
                   AND t.name NOT LIKE 'Підготовка ГКЛ%%'
                   AND (SELECT count(*) FROM estimate_template_items i WHERE i.template_id = t.id) < 6
                """.formatted(inList(TRADES)), String.class)).isEmpty();
    }

    /** Ruling 2 (one wording for protection and debris) and ruling 3 (no percentage, no warranty
     *  visit a master forgets to delete). */
    @Test
    void noDefaultBundleCarriesARetiredWordingOrAConditionalPercentage() {
        assertThat(db.queryForList("""
                SELECT t.trade || ' | ' || t.name || ' | ' || i.name
                  FROM estimate_templates t JOIN estimate_template_items i ON i.template_id = t.id
                 WHERE t.owner_id IS NULL AND t.is_default AND t.trade IN (%s)
                   AND (i.unit = 'PERCENT'
                        OR i.name IN ('Укриття плівкою підлоги, дверей, сантехніки',
                                      'Збирання сміття в мішки після демонтажу',
                                      'Гарантійний повторний виїзд'))
                """.formatted(inList(TRADES)), String.class)).isEmpty();
    }

    @Test
    void anIndoorJobOpensWithProtectionAndClosesWithTheCleanup() {
        List<String> bathroom = lines("TILING", "Санвузол «під ключ»");
        assertThat(bathroom).as("the floor is covered before the first tile comes off")
                .containsSubsequence("Захист підлоги картоном", "Демонтаж плитки");
        assertThat(bathroom.get(bathroom.size() - 1)).isEqualTo("Прибирання приміщення після робіт");

        List<String> rewiring = lines("ELECTRICAL", "Заміна проводки в квартирі");
        assertThat(rewiring.get(rewiring.size() - 1)).isEqualTo("Прибирання приміщення після робіт");
    }

    /** Ruling 4: the apartment is distributed through a collector, right after the inlet. */
    @Test
    void theApartmentIsDistributedThroughACollector() {
        assertThat(lines("PLUMBING", "Квартира — вузол вводу, розводка води й каналізації"))
                .containsSubsequence("Монтаж редуктора тиску води",
                        "Монтаж колектора водопостачання (гребінки) з кранами на виходах",
                        "Перевірка системи водопроводу тиском",
                        "Заробка штроб (сантехніка)",
                        "Запуск системи водопроводу");
    }

    /** The rafter system V146 had to leave out of the pitched roof, now that it exists. */
    @Test
    void thePitchedRoofHasItsRafters() {
        assertThat(lines("BUILDER", "Покрівля двоскатна"))
                .containsSubsequence("Монтаж маурлата", "Монтаж кроквяної системи",
                        "Монтаж контррейки по кроквах (крок 600 мм)", "Укладання металочерепиці");
    }

    /** A construction bundle ends at the boards; the joints come from the Q-level applied with it
     *  (ruling 1) — so applying both no longer prices the putty twice. */
    @Test
    void aDrywallConstructionBundleLeavesTheJointsToTheFinishLevel() {
        for (String bundle : List.of("Стеля з гіпсокартону", "Стіни та перегородки з гіпсокартону")) {
            assertThat(lines("DRYWALL", bundle)).as(bundle)
                    .noneMatch(line -> line.startsWith("Заповнення") || line.startsWith("Шпаклювання"));
        }
    }

    // =============================================================================================
    // What a master already had
    // =============================================================================================

    /** A fork of a retired dump is HIS template now: the override cascades away with the default,
     *  the copy stays and keeps every line he had. */
    @Test
    void aForkOfARetiredDumpSurvivesAsHisOwnTemplate() {
        assertThat(count("SELECT count(*) FROM estimate_templates WHERE id = ?", dumpFork)).isOne();
        assertThat(count("SELECT count(*) FROM estimate_template_items WHERE template_id = ?", dumpFork))
                .isEqualTo(104);
        assertThat(count("SELECT count(*) FROM template_default_override WHERE user_id = ?",
                UUID.fromString(DUMP_FORKER))).isZero();
    }

    /** A bundle renamed IN PLACE keeps its id, so the override that hides it for a forker still
     *  hides it — he does not suddenly see the default beside his own copy. */
    @Test
    void aRenamedBundleStaysHiddenForTheMasterWhoForkedIt() {
        assertThat(db.queryForObject("SELECT name FROM estimate_templates WHERE id = ?", String.class,
                boilerRoom)).isEqualTo("Котельня під ключ");
        assertThat(count("""
                SELECT count(*) FROM template_default_override WHERE user_id = ? AND template_id = ?
                """, UUID.fromString(RENAME_FORKER), boilerRoom)).isOne();
    }

    /** One name arriving under three of his trades at once lands ONCE, under the trade V118 ranks
     *  first — the read path files it for the other two chips. */
    @Test
    void aNameSeveralOfHisTradesShipArrivesOnce() {
        assertThat(db.queryForList("""
                SELECT trade FROM catalog_items WHERE owner_id = ? AND name = 'Захист підлоги картоном'
                """, String.class, UUID.fromString(MULTI))).containsExactly("PLUMBING");
    }

    @Test
    void aRowHePricedHimselfIsNeitherOverwrittenNorDoubled() {
        assertThat(db.queryForList("""
                SELECT default_price FROM catalog_items
                 WHERE owner_id = ? AND lower(name) = 'фасування сміття в мішки'
                """, BigDecimal.class, UUID.fromString(OWN_PRICE)))
                .singleElement().satisfies(p -> assertThat(p).isEqualByComparingTo("25.00"));
    }

    @Test
    void everyMasterHearsAboutItOnceAndIsSyncedToTheNewVersion() {
        int version = db.queryForObject("SELECT MAX(added_in_version) FROM catalog_templates",
                Integer.class);
        assertThat(version).isEqualTo(versionBefore + 1);
        for (String master : List.of(MULTI, OWN_PRICE, DUMP_FORKER, RENAME_FORKER)) {
            assertThat(count("""
                    SELECT count(*) FROM catalog_update_notices
                     WHERE user_id = ? AND kind = 'COUNT' AND dismissed_at IS NULL
                    """, UUID.fromString(master))).as(master).isOne();
            assertThat(db.queryForObject(
                    "SELECT last_synced_catalog_version FROM users WHERE id = ?", Integer.class,
                    UUID.fromString(master))).as(master).isEqualTo(version);
        }
    }

    // =============================================================================================
    // The norms that ride along
    // =============================================================================================

    @Test
    void theCeilingWoolMovedToItsOwnPosition() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'каркасна звукоізоляція (гкл в два слоя) стелі'
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'MINERAL_WOOL'
                   AND n.name_key = 'звукоізоляція стелі мінеральною ватою'
                """)).isOne();
    }

    /** Reveal primer is bought by its own step, which stands before every painted reveal. */
    @Test
    void aRevealIsPrimedByItsOwnStepAndThePaintBuysNoPrimer() {
        assertThat(count("""
                SELECT count(*) FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND m.code = 'PRIMER_DEEP'
                   AND n.name_key IN ('фарбування укосів',
                                      'шпаклівка коробів, укосів, ніш та виступів під фарбування')
                """)).isZero();
        assertThat(db.queryForList("""
                SELECT t.name FROM estimate_templates t
                  JOIN estimate_template_items paint ON paint.template_id = t.id
                                                    AND paint.name = 'Фарбування укосів'
                 WHERE t.owner_id IS NULL AND t.is_default
                   AND NOT EXISTS (SELECT 1 FROM estimate_template_items p
                                    WHERE p.template_id = t.id
                                      AND p.name = 'Обезпилення та грунтування укосів перед фарбуванням'
                                      AND p.sort_order < paint.sort_order)
                """, String.class)).as("bundles painting a reveal nobody primed").isEmpty();
    }

    /** Variant A: a bag line's quantity IS the number of bags, in every trade. */
    @Test
    void aBagLineBuysItsBags() {
        assertThat(db.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                 WHERE n.owner_id IS NULL AND n.trade IS NULL AND m.code = 'DEBRIS_BAG'
                   AND n.name_key = 'фасування сміття в мішки' AND n.unit = 'PIECE'
                """, BigDecimal.class)).isEqualByComparingTo("1");
    }

    private static String inList(List<String> values) {
        return String.join(", ", values.stream().map(v -> "'" + v + "'").toList());
    }
}
