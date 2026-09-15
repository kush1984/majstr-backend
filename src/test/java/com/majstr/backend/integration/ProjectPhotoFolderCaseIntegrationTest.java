package com.majstr.backend.integration;

import com.majstr.backend.entity.ProjectPhotoFolder;
import com.majstr.backend.repository.ProjectPhotoFolderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review item B-27: a custom photo folder's identity is case-insensitive, the two reserved aliases
 * always were, and the mismatch let «фасад» become a second folder beside «Фасад» — splitting the
 * master's photos across two entries he cannot tell apart in the list.
 *
 * <p>The fix has two halves and only the database can be asked about the second: the service now
 * looks folders up ignoring case, and V133 replaced {@code UNIQUE (project_id, name)} with a
 * functional unique index on {@code (project_id, lower(btrim(name)))}. A service-level guard alone
 * would still lose a race between two uploads, which is exactly how a twin got created.</p>
 */
class ProjectPhotoFolderCaseIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectPhotoFolderRepository folderRepository;

    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua",
                ownerId.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
    }

    /**
     * The container's schema is shared with every other integration test, so this cleans up by OWNER
     * rather than by the one project — a test that creates a second object must not leave it behind
     * when an assertion fails before its own cleanup line.
     */
    @AfterEach
    void clean() {
        jdbc.update("""
                DELETE FROM project_photo_folder
                 WHERE project_id IN (SELECT id FROM projects WHERE owner_id = ?)
                """, ownerId);
        jdbc.update("DELETE FROM projects WHERE owner_id = ?", ownerId);
        jdbc.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    @Test
    void aFolderSpelledInAnotherCaseCannotBeCreatedTwice() {
        folderRepository.saveAndFlush(folder("Фасад"));

        assertThatThrownBy(() -> folderRepository.saveAndFlush(folder("фасад")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Padding is part of the same key: the index compares {@code lower(btrim(name))}. */
    @Test
    void aFolderPaddedWithSpacesIsTheSameFolder() {
        folderRepository.saveAndFlush(folder("Фасад"));

        assertThatThrownBy(() -> folderRepository.saveAndFlush(folder("  ФАСАД ")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The read side of the same rule. The service files a photo under the name this query returns,
     * so the ORIGINAL spelling has to come back — the master named the folder, not us.
     */
    @Test
    void theLookupIgnoresCaseAndAnswersWithTheStoredSpelling() {
        folderRepository.saveAndFlush(folder("Фасад"));

        assertThat(folderRepository.findByProjectIdAndNameIgnoreCase(projectId, "ФАСАД"))
                .get()
                .extracting(ProjectPhotoFolder::getName)
                .isEqualTo("Фасад");
    }

    /** Two objects are separate namespaces — the index is scoped by project_id. */
    @Test
    void theSameNameOnAnotherObjectIsAnotherFolder() {
        folderRepository.saveAndFlush(folder("Фасад"));
        UUID otherProject = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Будинок', 'вул. Тестова 2', 'IN_PROGRESS')
                """, otherProject, ownerId);

        folderRepository.saveAndFlush(ProjectPhotoFolder.builder()
                .projectId(otherProject).name("Фасад").build());

        assertThat(folderRepository.findByProjectIdAndNameIgnoreCase(otherProject, "фасад")).isPresent();
    }

    private ProjectPhotoFolder folder(String name) {
        return ProjectPhotoFolder.builder().projectId(projectId).name(name).build();
    }
}
