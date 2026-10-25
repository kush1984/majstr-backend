package com.majstr.backend.integration;

import com.majstr.backend.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a message that carries attachments, through the real Hibernate session (Sentry
 * JAVA-SPRING-BOOT-P). The files were loaded to collect their keys, and the managed rows left pointing
 * at the removed message failed the flush — a 500 a mocked repository could never show.
 */
class MessageDeleteIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired MessageService messageService;

    private UUID owner;
    private UUID project;
    private UUID messageId;

    @BeforeEach
    void seedAnObjectWithAMessage() {
        owner = UUID.randomUUID();
        project = UUID.randomUUID();
        messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status)"
                + " VALUES (?, ?, 'Квартира', 'вул. 1', 'IN_PROGRESS')", project, owner);
        jdbc.update("""
                INSERT INTO project_messages (id, project_id, author_name, message, is_read, created_at)
                VALUES (?, ?, 'Постачальник', 'Рахунок', false, NOW())
                """, messageId, project);
    }

    private void attach(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_message_files
                    (id, message_id, storage_key, original_name, content_type, size_bytes, created_at)
                VALUES (?, ?, ?, ?, 'application/pdf', 1024, NOW())
                """, id, messageId, "messages/" + id + ".pdf", name);
    }

    private int count(String table, String column, UUID id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    @Test
    void aMessageWithAttachmentsIsDeletedWithItsFiles() {
        attach("рахунок.pdf");
        attach("фото.pdf");

        messageService.delete(project, messageId, owner);

        assertThat(count("project_messages", "id", messageId)).isZero();
        assertThat(count("project_message_files", "message_id", messageId)).isZero();
    }

    @Test
    void aMessageWithoutAttachmentsIsDeleted() {
        messageService.delete(project, messageId, owner);

        assertThat(count("project_messages", "id", messageId)).isZero();
    }
}
