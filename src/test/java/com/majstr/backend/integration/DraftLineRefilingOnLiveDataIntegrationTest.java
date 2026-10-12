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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V140 re-files a DRAFT line that a shared catalog position stamped with somebody else's trade
 * (review B-42). Its rules were held by nothing but the migration's own header: a scratch database
 * migrated to V139, seeded with every shape V140 has to answer, then migrated to V140 — capped
 * there, because a later migration may move the same rows for its own reasons.
 *
 * <p>The 50/50 tie is pinned AS SHIPPED: the owner ruled (2026-10-01) to leave V140's tie choices as
 * they are, so a tie breaks to the alphabetically first trade. That is what this file records, not
 * an endorsement — V144's header carries the corrected rule for any future re-run.</p>
 *
 * <p>The positions are read off the library at V139 rather than typed here, so the test cannot
 * drift from the data it describes: one name PAINTER and DRYWALL both ship, and one only DRYWALL
 * ships.</p>
 */
class DraftLineRefilingOnLiveDataIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_before_v140";
    private static final String OWNER = "14014014-0140-0140-0140-140140140140";
    private static final String PROJECT = "aaaaaaaa-0140-0140-0140-aaaaaaaaaaaa";

    private static final String MAJORITY = "b1000000-0140-0140-0140-000000000001";
    private static final String TIE = "b2000000-0140-0140-0140-000000000002";
    private static final String NO_MAJORITY = "b3000000-0140-0140-0140-000000000003";
    private static final String SIGNED = "b4000000-0140-0140-0140-000000000004";
    private static final String SENT = "b5000000-0140-0140-0140-000000000005";

    private static final UUID MOVED = UUID.randomUUID();
    private static final UUID NOT_SHIPPED_BY_MAJORITY = UUID.randomUUID();
    private static final UUID NULL_TRADE = UUID.randomUUID();
    private static final UUID TIE_MOVED = UUID.randomUUID();
    private static final UUID NO_MAJORITY_KEPT = UUID.randomUUID();
    private static final UUID SIGNED_KEPT = UUID.randomUUID();
    private static final UUID SENT_KEPT = UUID.randomUUID();

    private static JdbcTemplate db;
    /** Shipped by both PAINTER and DRYWALL. */
    private static Map<String, Object> shared;
    private static String sharedPainterCategory;
    /** Shipped by DRYWALL and not by PAINTER. */
    private static Map<String, Object> drywallOnly;

    @BeforeAll
    static void migrateToV139_seed_thenV140() throws SQLException {
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

        migrateTo(url, user, pass, "139");
        readTheLibrary();
        seed();
        migrateTo(url, user, pass, "140");
    }

    private static void migrateTo(String url, String user, String pass, String version) {
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load().migrate();
    }

    private static void readTheLibrary() {
        shared = db.queryForMap("""
                SELECT p.name, p.type, p.unit, p.category
                  FROM catalog_templates p
                  JOIN catalog_templates d ON lower(btrim(d.name)) = lower(btrim(p.name))
                                          AND d.type = p.type AND d.unit = p.unit
                 WHERE p.trade = 'PAINTER' AND d.trade = 'DRYWALL'
                 ORDER BY p.sort_order, p.name
                 LIMIT 1
                """);
        sharedPainterCategory = (String) shared.get("category");
        drywallOnly = db.queryForMap("""
                SELECT d.name, d.type, d.unit
                  FROM catalog_templates d
                 WHERE d.trade = 'DRYWALL'
                   AND NOT EXISTS (SELECT 1 FROM catalog_templates p
                                    WHERE p.trade = 'PAINTER'
                                      AND lower(btrim(p.name)) = lower(btrim(d.name))
                                      AND p.type = d.type AND p.unit = d.unit)
                 ORDER BY d.sort_order, d.name
                 LIMIT 1
                """);
    }

    private static void seed() {
        db.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?::uuid, 'owner@v140.test', 'owner@v140.test', 'x', 'Майстер', '+380', 'ФОП', 'V140A')
                """, OWNER);
        db.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?::uuid, ?::uuid, 'Обʼєкт', 'Львів', 'IN_PROGRESS')
                """, PROJECT, OWNER);
        estimate(MAJORITY, "DRAFT");
        estimate(TIE, "DRAFT");
        estimate(NO_MAJORITY, "DRAFT");
        estimate(SIGNED, "SIGNED");
        estimate(SENT, "SENT");

        // A painting draft: three PAINTER lines, the shared position under DRYWALL, a DRYWALL-only
        // position and a hand-typed line with no trade. 3 of 5 trade-bearing lines = a majority.
        own(MAJORITY, "PAINTER", 3);
        line(MOVED, MAJORITY, shared, "DRYWALL", "Чужа категорія");
        line(NOT_SHIPPED_BY_MAJORITY, MAJORITY, drywallOnly, "DRYWALL", "Каркас");
        line(NULL_TRADE, MAJORITY, shared, null, null);

        // One DRYWALL line and the shared position under PAINTER: 1/1, a tie.
        own(TIE, "DRYWALL", 1);
        line(TIE_MOVED, TIE, shared, "PAINTER", "Підготовка");

        // Three trades, one line each: nobody has half.
        own(NO_MAJORITY, "DRYWALL", 1);
        own(NO_MAJORITY, "TILING", 1);
        line(NO_MAJORITY_KEPT, NO_MAJORITY, shared, "PAINTER", "Підготовка");

        // The majority case again, on documents the client has already seen.
        own(SIGNED, "PAINTER", 3);
        line(SIGNED_KEPT, SIGNED, shared, "DRYWALL", "Чужа категорія");
        own(SENT, "PAINTER", 3);
        line(SENT_KEPT, SENT, shared, "DRYWALL", "Чужа категорія");
    }

    private static void estimate(String id, String status) {
        db.update("""
                INSERT INTO estimates (id, project_id, status, name)
                VALUES (?::uuid, ?::uuid, ?, ?)
                """, id, PROJECT, status, "Кошторис " + status);
    }

    /** {@code n} lines of a name no library carries, so they count toward the majority and never move. */
    private static void own(String estimateId, String trade, int n) {
        for (int i = 0; i < n; i++) {
            line(UUID.randomUUID(), estimateId,
                    Map.of("name", "Власна позиція " + trade + " " + i, "type", "WORK", "unit", "M2"),
                    trade, "Власна");
        }
    }

    private static void line(UUID id, String estimateId, Map<String, Object> position,
                             String trade, String category) {
        db.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade, category)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, 1, 100, 100, 0, ?, ?)
                """, id, estimateId, position.get("type"), position.get("name"), position.get("unit"),
                trade, category);
    }

    private static Map<String, Object> filing(UUID lineId) {
        return db.queryForMap("SELECT trade, category FROM estimate_items WHERE id = ?::uuid", lineId);
    }

    // =============================================================================================

    @Test
    void aSharedPositionInADraft_isRefiledUnderTheMajorityTrade_withItsCategory() {
        assertThat(filing(MOVED))
                .containsEntry("trade", "PAINTER")
                .containsEntry("category", sharedPainterCategory);
    }

    @Test
    void aPositionTheMajorityTradeDoesNotShip_staysWhereItWas() {
        assertThat(filing(NOT_SHIPPED_BY_MAJORITY))
                .containsEntry("trade", "DRYWALL")
                .containsEntry("category", "Каркас");
    }

    @Test
    void aLineWithNoTrade_isNeverGivenOne() {
        assertThat(filing(NULL_TRADE).get("trade")).isNull();
    }

    @Test
    void aTie_breaksToTheAlphabeticallyFirstTrade_asShipped() {
        assertThat(filing(TIE_MOVED)).containsEntry("trade", "DRYWALL");
    }

    @Test
    void belowHalf_hasNoOpinion() {
        assertThat(filing(NO_MAJORITY_KEPT))
                .containsEntry("trade", "PAINTER")
                .containsEntry("category", "Підготовка");
    }

    @Test
    void signedAndSentEstimates_areNeverRewritten() {
        assertThat(filing(SIGNED_KEPT)).containsEntry("trade", "DRYWALL");
        assertThat(filing(SENT_KEPT)).containsEntry("trade", "DRYWALL");
    }
}
