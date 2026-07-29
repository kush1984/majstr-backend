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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V74 moves client questions onto the object, and this checks it on a database that already HAS some —
 * which is the only interesting case, and the one a clean-database test cannot express.
 *
 * <p>Also pins the two things the Java compiler cannot see. The column is NOT NULL, and the entity is
 * built through a Lombok builder, so forgetting to set the project compiles fine and fails on a real
 * client asking a real question. And the estimate FK stops cascading: deleting one quote must forget
 * which quote was discussed, not delete the conversation about the job.</p>
 */
class ProjectMessagesMigrationIntegrationTest extends IntegrationTestBase {

    private static final String DB = "majstr_messages_migration";
    private static final String OWNER = "44444444-4444-4444-4444-444444444444";
    private static final String PROJECT = "55555555-5555-5555-5555-555555555555";
    private static final String ESTIMATE = "66666666-6666-6666-6666-666666666666";
    private static final String QUESTION = "77777777-7777-7777-7777-777777777777";

    private static JdbcTemplate legacy;

    @BeforeAll
    static void migrateADatabaseThatAlreadyHasQuestions() throws SQLException {
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

        // The release before this one, with a question already sitting on an estimate.
        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("73"))
                .load().migrate();
        seedAQuestion();

        Flyway.configure().dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private static void seedAQuestion() {
        legacy.execute("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES ('%s', 'msg@test.ua', 'msg@test.ua', 'x', 'Майстер', '+380', 'ФОП', 'MSG1')
                """.formatted(OWNER));
        legacy.execute("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES ('%s', '%s', 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')
                """.formatted(PROJECT, OWNER));
        legacy.execute("""
                INSERT INTO estimates (id, project_id, status, name)
                VALUES ('%s', '%s', 'DRAFT', 'Варіант А')
                """.formatted(ESTIMATE, PROJECT));
        legacy.execute("""
                INSERT INTO estimate_questions (id, estimate_id, author_name, author_phone, message, is_read)
                VALUES ('%s', '%s', 'Василь', '+380671112233', 'Коли починаєте?', false)
                """.formatted(QUESTION, ESTIMATE));
    }

    // =============================================================================================

    @Test
    void anExistingQuestionKeepsItsIdAndGainsTheObjectItBelongsTo() {
        Map<String, Object> row = legacy.queryForMap(
                "SELECT project_id, estimate_id, author_name, message, is_read"
                        + " FROM project_messages WHERE id = ?", UUID.fromString(QUESTION));

        assertThat(row.get("project_id").toString())
                .as("привʼязка до обʼєкта, доліфтована з кошторису").isEqualTo(PROJECT);
        assertThat(row.get("estimate_id").toString())
                .as("який саме кошторис обговорювали — лишається як контекст").isEqualTo(ESTIMATE);
        assertThat(row.get("author_name")).isEqualTo("Василь");
        assertThat(row.get("message")).isEqualTo("Коли починаєте?");
        assertThat(row.get("is_read")).as("непрочитане лишається непрочитаним").isEqualTo(false);
    }

    @Test
    void aMessageWithNoEstimateIsNowStorable() {
        // The whole point of V74: what comes through the master's link has no estimate at all, and
        // before this the column was NOT NULL.
        legacy.execute("""
                INSERT INTO project_messages (id, project_id, author_name, message, is_read, created_at)
                VALUES ('88888888-8888-8888-8888-888888888888', '%s', 'Постачальник',
                        'Рахунок у вкладенні', false, NOW())
                """.formatted(PROJECT));

        assertThat(legacy.queryForObject(
                "SELECT count(*) FROM project_messages WHERE project_id = ? AND estimate_id IS NULL",
                Integer.class, UUID.fromString(PROJECT))).isEqualTo(1);
    }

    @Test
    void deletingTheEstimateForgetsTheContextButKeepsTheMessage() {
        // The FK used to CASCADE. On an object-scoped message that would delete the conversation
        // about the job because one of several quotes was removed.
        legacy.execute("DELETE FROM estimates WHERE id = '" + ESTIMATE + "'");

        Map<String, Object> row = legacy.queryForMap(
                "SELECT estimate_id, message FROM project_messages WHERE id = ?", UUID.fromString(QUESTION));
        assertThat(row.get("estimate_id")).as("контекст забувається").isNull();
        assertThat(row.get("message")).as("саме повідомлення лишається").isEqualTo("Коли починаєте?");
    }

    @Test
    void theOldIndexNamesNoLongerLieAboutTheirTable() {
        List<String> indexes = legacy.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'project_messages'", String.class);

        assertThat(indexes)
                .as("індекси й первинний ключ не мусять називатись іменем старої таблиці")
                .noneMatch(name -> name.contains("estimate_questions"));
        assertThat(indexes).contains("idx_project_messages_project_unread", "project_messages_pkey");

        List<String> constraints = legacy.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'project_messages'::regclass",
                String.class);
        assertThat(constraints).noneMatch(name -> name.contains("estimate_questions"));
        assertThat(constraints)
                .contains("project_messages_project_fk", "project_messages_estimate_fk");
    }
}
