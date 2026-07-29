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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V75 splits share links into two kinds on a database that already has portal links in it.
 *
 * <p>The one that would hurt is the backfill going wrong: a live portal link that came out as anything
 * other than PORTAL stops resolving, and a client who bookmarked their estimate gets a 404 with nothing
 * in the log to explain it. Which kind each lookup asks for is pinned in the service unit tests; what is
 * only observable against a real Postgres is here — the backfill, the CHECK, and the index swap.</p>
 */
class ShareLinkKindMigrationIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_share_link_kind";
    private static final String OWNER = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PROJECT = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String LINK = "aaaaaaaa-0000-0000-0000-000000000003";

    private static JdbcTemplate legacy;

    @BeforeAll
    static void migrateADatabaseThatAlreadyHasAPortalLink() throws SQLException {
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                + "/" + DB;
        legacy = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("74"))
                .load().migrate();
        seedAPortalLink();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedAPortalLink() {
        legacy.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'kind@test.ua', 'kind@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'KND1')
                """.formatted(OWNER));
        legacy.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, OWNER));
        legacy.execute("""
                INSERT INTO project_share_links (id, project_id, token)
                VALUES ('%s', '%s', 'live-portal-token')
                """.formatted(LINK, PROJECT));
    }

    // =============================================================================================

    @Test
    void aLinkThatExistedBeforeTheSplitIsAPortalLink() {
        // The client already has this URL. It has to keep opening their estimate.
        String kind = legacy.queryForObject(
                "SELECT kind FROM project_share_links WHERE id = ?", String.class, UUID.fromString(LINK));

        assertThat(kind).as("наявне посилання — портальне, і лишається робочим").isEqualTo("PORTAL");
    }

    @Test
    void anInsertThatForgetsTheKindGetsThePortalDefault() {
        // The default is deliberate: PORTAL is the kind that reveals nothing new, so a forgotten `kind`
        // fails safe rather than silently minting a link of the other sort.
        legacy.execute("""
                INSERT INTO project_share_links (id, project_id, token)
                VALUES ('aaaaaaaa-0000-0000-0000-000000000004', '%s', 'no-kind-token')
                """.formatted(PROJECT));

        assertThat(legacy.queryForObject(
                "SELECT kind FROM project_share_links WHERE token = 'no-kind-token'", String.class))
                .isEqualTo("PORTAL");
    }

    @Test
    void bothKindsCanBeLiveForTheSameObject() {
        // The point of the whole migration: the master hands the client one link and a supplier another,
        // at the same time, for the same job.
        legacy.execute("""
                INSERT INTO project_share_links (id, project_id, token, kind)
                VALUES ('aaaaaaaa-0000-0000-0000-000000000005', '%s', 'msg-token', 'MESSAGE')
                """.formatted(PROJECT));

        List<String> kinds = legacy.queryForList(
                "SELECT kind FROM project_share_links WHERE project_id = ? AND revoked = FALSE"
                        + " ORDER BY kind", String.class, UUID.fromString(PROJECT));
        assertThat(kinds).contains("MESSAGE", "PORTAL");
    }

    @Test
    void anUnknownKindIsRejectedByTheDatabase() {
        // The enum lives in Java, so a typo in a migration or a hand-written UPDATE is the realistic way
        // a third kind appears. Then `findByTokenAndKind` matches nothing and the link is simply dead.
        assertThatThrownBy(() -> legacy.execute("""
                INSERT INTO project_share_links (id, project_id, token, kind)
                VALUES ('aaaaaaaa-0000-0000-0000-000000000006', '%s', 'bad-token', 'PORTAL2')
                """.formatted(PROJECT)))
                .hasMessageContaining("project_share_links_kind_check");
    }

    @Test
    void theProjectOnlyIndexIsReplacedByTheKindAwareOne() {
        List<String> indexes = legacy.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'project_share_links'", String.class);

        assertThat(indexes).contains("idx_project_share_links_project_kind");
        assertThat(indexes)
                .as("старий індекс лишень по project_id не вміє відповісти «живе посилання ЦЬОГО типу»")
                .doesNotContain("idx_project_share_links_project_id");
    }
}
