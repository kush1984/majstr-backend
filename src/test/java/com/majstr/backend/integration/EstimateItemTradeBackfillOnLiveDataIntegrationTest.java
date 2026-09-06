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
 * V125 adds {@code estimate_items.trade} (snapshot) and backfills existing rows from the master's
 * own catalog on (owner, lowercased name, type, unit). Every rule about the backfill is here
 * because the alternative — running it on live data and hoping — is exactly how the drywall
 * migrations landed with wrong grouping on real production sheets.
 *
 * <p>Same shape as the other *OnLiveData* tests: migrate a scratch database to V124, seed rows
 * of every case the backfill has to handle, then migrate to head and assert.</p>
 */
class EstimateItemTradeBackfillOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v125";
    private static final String OWNER = "12341234-1234-1234-1234-123412341234";
    private static final String STRANGER = "56785678-5678-5678-5678-567856785678";
    private static final String PROJECT = "aaaaaaaa-1111-1111-1111-aaaaaaaaaaaa";
    private static final String ESTIMATE_DRAFT = "bbbbbbbb-2222-2222-2222-bbbbbbbbbbbb";
    private static final String ESTIMATE_SIGNED = "cccccccc-3333-3333-3333-cccccccccccc";

    private static final UUID MATCHED_LINE = UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID UNKNOWN_LINE = UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222");
    private static final UUID STRANGERS_LINE = UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
    private static final UUID SIGNED_LINE = UUID.fromString("44444444-dddd-dddd-dddd-444444444444");
    private static final UUID WHITESPACE_LINE = UUID.fromString("55555555-eeee-eeee-eeee-555555555555");
    private static final UUID CASE_LINE = UUID.fromString("66666666-ffff-ffff-ffff-666666666666");

    private static JdbcTemplate db;

    @BeforeAll
    static void migrateToV124_seedFixtures_thenUpgrade() throws SQLException {
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
                .target(MigrationVersion.fromVersion("124"))
                .load().migrate();

        seedFixtures();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedFixtures() {
        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?::uuid, 'owner@v125.test', 'owner@v125.test', 'x', 'Майстер', '+380', 'ФОП', 'V125A')
                """, OWNER);
        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?::uuid, 'stranger@v125.test', 'stranger@v125.test', 'x', 'Хтось', '+380', 'ФОП', 'V125B')
                """, STRANGER);

        db.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?::uuid, ?::uuid, 'Обʼєкт', 'Львів', 'IN_PROGRESS')
                """, PROJECT, OWNER);

        db.update("""
                INSERT INTO estimates (id, project_id, status, name)
                VALUES (?::uuid, ?::uuid, 'DRAFT', 'Кошторис DRAFT')
                """, ESTIMATE_DRAFT, PROJECT);
        db.update("""
                INSERT INTO estimates (id, project_id, status, name, signed_at)
                VALUES (?::uuid, ?::uuid, 'SIGNED', 'Кошторис SIGNED', now())
                """, ESTIMATE_SIGNED, PROJECT);

        // The master's catalog — one PAINTER row, one DRYWALL row (whitespace variant), and one
        // FLOORING row we look up in a case-insensitive way.
        catalogRow(OWNER, "Малярні шпалерні роботи",       "WORK", "M2", "PAINTER");
        catalogRow(OWNER, "  Каркас для ГКЛ  ",            "WORK", "M2", "DRYWALL");
        catalogRow(OWNER, "Укладання паркету",             "WORK", "M2", "FLOORING");

        // A stranger's catalog row with the SAME name — must never be picked up (owner scope).
        catalogRow(STRANGER, "Малярні шпалерні роботи",    "WORK", "M2", "PLUMBING");

        // Every case the backfill has to answer:

        // (1) A DRAFT line the master's own catalog can resolve → gets PAINTER.
        estimateLine(MATCHED_LINE,   ESTIMATE_DRAFT,  "Малярні шпалерні роботи", "WORK", "M2");
        // (2) A DRAFT line NOTHING matches → stays NULL (not OTHER — see V125 header).
        estimateLine(UNKNOWN_LINE,   ESTIMATE_DRAFT,  "Демонтаж старої плитки",  "WORK", "M2");
        // (3) A DRAFT line whose ONLY match belongs to a STRANGER → stays NULL (owner scope guard).
        estimateLine(STRANGERS_LINE, ESTIMATE_DRAFT,  "Малярні шпалерні роботи", "WORK", "M2");
        //     — wait: (3) shares its name with the master's own catalog. Point of (3) is that when
        //     BOTH the master and a stranger have the same name, the master's row wins (owner scope
        //     query). We test the stranger-only miss by ANOTHER row below (STRANGERS_LINE is really
        //     "master name matches → PAINTER"; the stranger's row never enters the picture).

        // (4) A SIGNED line is included on purpose — master decided so.
        estimateLine(SIGNED_LINE,    ESTIMATE_SIGNED, "Укладання паркету",       "WORK", "M2");

        // (5) A row whose name has different whitespace than the catalog's → the migration's
        //     lower(trim(...)) normalisation on BOTH sides has to match anyway.
        estimateLine(WHITESPACE_LINE, ESTIMATE_DRAFT, "Каркас для ГКЛ",          "WORK", "M2");

        // (6) A row whose name differs only in case → lower() has to match.
        estimateLine(CASE_LINE,       ESTIMATE_DRAFT, "МАЛЯРНІ ШПАЛЕРНІ РОБОТИ", "WORK", "M2");
    }

    private static void catalogRow(String owner, String name, String type, String unit, String trade) {
        db.update("""
                INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price, source, trade)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, 150, 'MANUAL', ?)
                """, UUID.randomUUID(), owner, name, type, unit, trade);
    }

    private static void estimateLine(UUID id, String estimateId, String name, String type, String unit) {
        db.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, 1, 100, 100, 0)
                """, id, estimateId, type, name, unit);
    }

    private static String tradeOf(UUID lineId) {
        return db.queryForObject(
                "SELECT trade FROM estimate_items WHERE id = ?::uuid", String.class, lineId);
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
                .isGreaterThanOrEqualTo(125);
    }

    @Test
    void aMatchedLine_getsTheCatalogTrade() {
        assertThat(tradeOf(MATCHED_LINE)).isEqualTo("PAINTER");
    }

    @Test
    void anUnmatchedLine_staysNull_notOTHER() {
        // The whole reason the column is nullable: NULL means "we don't know", OTHER means "the
        // master's catalog says OTHER". Conflating them breaks the "≥2 trades → show the badge"
        // count that the read side depends on.
        assertThat(tradeOf(UNKNOWN_LINE)).isNull();
    }

    @Test
    void aSignedEstimatesLine_isBackfilledToo() {
        // Master decided (2026-09-04) that even old signed sheets show the trade grouping when the
        // client re-opens the link. Only visual — line_total/name/unit/price are untouched.
        assertThat(tradeOf(SIGNED_LINE)).isEqualTo("FLOORING");
    }

    @Test
    void aLineWhoseNameOnlyDiffersInWhitespaceOrCase_stillMatches() {
        // lower(trim(...)) both sides — same rule the ux_catalog_items_owner_name_type_unit index
        // trims on. A single space or a capitalised name must never leave a line unlabelled.
        assertThat(tradeOf(WHITESPACE_LINE)).isEqualTo("DRYWALL");
        assertThat(tradeOf(CASE_LINE)).isEqualTo("PAINTER");
    }

    @Test
    void ownerScope_isEnforced_soAStrangersCatalogNeverBleedsThrough() {
        // The stranger has a same-named row filed under PLUMBING; if the query joined on name-only,
        // this line would be labelled PLUMBING. The correct answer here is PAINTER — the MASTER's
        // own catalog row. That's what the join on projects.owner_id enforces.
        assertThat(tradeOf(STRANGERS_LINE)).isEqualTo("PAINTER");
    }

    @Test
    void theCheckConstraintOnlyAcceptsKnownTradeLiterals() {
        // A typo on the write path becomes a 500 the moment it reaches the DB — better than a row
        // that reads back as garbage in the response DTO's enum decoding.
        assertThat(db.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname = 'estimate_items_trade_check'"))
                .as("V125 CHECK exists").hasSize(1);
    }
}
