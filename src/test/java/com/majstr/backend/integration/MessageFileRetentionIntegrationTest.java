package com.majstr.backend.integration;

import com.majstr.backend.entity.ProjectMessageFile;
import com.majstr.backend.repository.ProjectMessageFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which files the retention sweep actually picks up, against a real Postgres.
 *
 * <p>These two queries decide whether somebody's invoice is deleted, and both turn on things a mocked
 * repository cannot check: the COALESCE fallback for a file nobody ever opened, and the condition that
 * lets an opened file escape a standing warning. A mistake in either is silent — the sweep runs at 3am
 * and the master finds out months later.</p>
 */
class MessageFileRetentionIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectMessageFileRepository fileRepository;

    private UUID messageId;

    @BeforeEach
    void seedAnObjectWithAMessage() {
        jdbc.update("DELETE FROM project_message_files");
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?)
                """, owner, owner + "@t.ua", owner + "@t.ua", owner.toString().substring(0, 8));
        jdbc.update("INSERT INTO projects (id, owner_id, name, address, status)"
                + " VALUES (?, ?, 'Квартира', 'вул. 1', 'COMPLETED')", project, owner);
        jdbc.update("""
                INSERT INTO project_messages (id, project_id, author_name, message, is_read, created_at)
                VALUES (?, ?, 'Постачальник', 'Рахунок', true, NOW())
                """, messageId, project);
    }

    /** @param openedDaysAgo null = never opened; @param warnedDaysAgo null = not warned. */
    private UUID file(String name, int createdDaysAgo, Integer openedDaysAgo, Integer warnedDaysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_message_files
                    (id, message_id, storage_key, original_name, content_type, size_bytes,
                     created_at, last_opened_at, deletion_warned_at)
                VALUES (?, ?, ?, ?, 'application/pdf', 1024, ?, ?, ?)
                """,
                id, messageId, "messages/" + id + ".pdf", name,
                java.sql.Timestamp.from(daysAgo(createdDaysAgo)),
                openedDaysAgo == null ? null : java.sql.Timestamp.from(daysAgo(openedDaysAgo)),
                warnedDaysAgo == null ? null : java.sql.Timestamp.from(daysAgo(warnedDaysAgo)));
        return id;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private List<String> dueForWarning(int retentionDays) {
        return fileRepository.findDueForWarning(daysAgo(retentionDays), PageRequest.of(0, 100))
                .stream().map(ProjectMessageFile::getOriginalName).toList();
    }

    private List<String> dueForDeletion(int graceDays) {
        return fileRepository.findDueForDeletion(daysAgo(graceDays), PageRequest.of(0, 100))
                .stream().map(ProjectMessageFile::getOriginalName).toList();
    }

    // =============================================================================================

    @Test
    void aFileNobodyEverOpenedAgesFromTheDayItArrived() {
        // The COALESCE fallback. On a plain `last_opened_at < cutoff` a never-opened file is NULL, the
        // comparison is unknown, and it would never be swept at all — immortal precisely because nobody
        // ever wanted it.
        file("старий-ніколи-не-відкривали.pdf", 200, null, null);
        file("свіжий-ніколи-не-відкривали.pdf", 10, null, null);

        assertThat(dueForWarning(180)).containsExactly("старий-ніколи-не-відкривали.pdf");
    }

    @Test
    void recentlyOpenedFilesAreLeftAloneEvenWhenOld() {
        file("старий-але-відкривали.pdf", 300, 5, null);

        assertThat(dueForWarning(180)).isEmpty();
    }

    @Test
    void aFileAlreadyWarnedAboutIsNotWarnedAgain() {
        // Otherwise the master gets the same notification every single night.
        file("вже-попереджено.pdf", 300, null, 3);

        assertThat(dueForWarning(180)).isEmpty();
    }

    @Test
    void deletionTakesOnlyWhatTheNoticeHasRunOutOn() {
        file("попереджено-давно.pdf", 300, null, 20);
        file("попереджено-щойно.pdf", 300, null, 2);
        file("не-попереджено.pdf", 300, null, null);

        assertThat(dueForDeletion(14)).containsExactly("попереджено-давно.pdf");
    }

    @Test
    void openingAWarnedFileSavesItFromTheDeletionPass() {
        // Belt and braces: the service clears the warning when the file is opened, and this query
        // refuses it anyway when it was opened after being warned. Either alone would do; both means a
        // caller that forgets to clear the flag cannot delete a file the master just looked at.
        file("попереджено-і-відкрито.pdf", 300, 1, 20);
        file("попереджено-і-забуто.pdf", 300, null, 20);

        assertThat(dueForDeletion(14)).containsExactly("попереджено-і-забуто.pdf");
    }

    @Test
    void theOldestFilesAreHandledFirst() {
        // The batch is bounded, so the order decides who gets warned tonight and who waits. Oldest
        // first, because that is the one most likely to be forgotten.
        file("середній.pdf", 200, null, null);
        file("найстаріший.pdf", 400, null, null);
        file("щойно-протермінований.pdf", 181, null, null);

        assertThat(dueForWarning(180))
                .containsExactly("найстаріший.pdf", "середній.pdf", "щойно-протермінований.pdf");
    }

    @Test
    void theSweepCanReachTheOwnerItHasToNotify() {
        // The warning names the object and goes to its master, so both are fetch-joined. Reading them
        // outside a transaction here proves they came back with the query rather than lazily.
        file("старий.pdf", 300, null, null);

        List<ProjectMessageFile> due =
                fileRepository.findDueForWarning(daysAgo(180), PageRequest.of(0, 100));

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getMessage().getProject().getName()).isEqualTo("Квартира");
        assertThat(due.get(0).getMessage().getProject().getOwner().getFullName()).isEqualTo("Майстер");
    }
}
