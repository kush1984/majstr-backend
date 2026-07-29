package com.majstr.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V76 guarantees about attachments, against a real Postgres.
 *
 * <p>The one that matters is the CASCADE. A master deleting a message expects the attachments to go
 * with it; if the FK did not cascade, the delete would fail on the constraint and the master would be
 * unable to remove a message at all — the kind of thing that only shows up once a message actually has
 * a file on it.</p>
 */
class MessageFilesMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private UUID seedMessage() {
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status)"
                + " VALUES (?, ?, 'Обʼєкт', 'вул. 1', 'IN_PROGRESS')", project, owner);
        jdbc.update("""
                INSERT INTO project_messages (id, project_id, author_name, message, is_read, created_at)
                VALUES (?, ?, 'Постачальник', 'Рахунок', false, NOW())
                """, message, project);
        return message;
    }

    private UUID seedFile(UUID messageId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_message_files
                    (id, message_id, storage_key, original_name, content_type, size_bytes)
                VALUES (?, ?, ?, ?, 'application/pdf', 1024)
                """, id, messageId, "messages/" + id + ".pdf", name);
        return id;
    }

    // =============================================================================================

    @Test
    void deletingAMessageTakesItsFilesWithIt() {
        UUID message = seedMessage();
        seedFile(message, "Рахунок.pdf");
        seedFile(message, "Фото.jpg");

        jdbc.update("DELETE FROM project_messages WHERE id = ?", message);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM project_message_files WHERE message_id = ?", Integer.class, message))
                .as("вкладення йдуть за повідомленням").isZero();
    }

    @Test
    void aFileCannotExistWithoutItsMessage() {
        UUID orphan = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO project_message_files
                    (id, message_id, storage_key, content_type, size_bytes)
                VALUES (?, ?, 'messages/x.pdf', 'application/pdf', 1)
                """, UUID.randomUUID(), orphan))
                .hasMessageContaining("project_message_files_message_fk");
    }

    @Test
    void aNewFileStartsWithNoOpenedStampAndAgesFromItsUpload() {
        // NULL is what "never opened" means, and the retention sweep falls back to created_at — so a
        // file nobody ever looks at still ages instead of being immortal.
        UUID message = seedMessage();
        UUID file = seedFile(message, "Рахунок.pdf");

        var row = jdbc.queryForMap(
                "SELECT last_opened_at, created_at, COALESCE(last_opened_at, created_at) AS touched"
                        + " FROM project_message_files WHERE id = ?", file);

        assertThat(row.get("last_opened_at")).isNull();
        assertThat(row.get("touched")).isEqualTo(row.get("created_at"));
    }

    @Test
    void theRetentionSweepHasAnIndexToUse() {
        // The sweep orders by COALESCE(last_opened_at, created_at) across every master's files. Without
        // the expression index that is a full scan of the table, run on a schedule forever.
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'project_message_files'",
                String.class);

        assertThat(indexes).contains(
                "idx_project_message_files_message_id",
                "idx_project_message_files_last_touched");
    }
}
