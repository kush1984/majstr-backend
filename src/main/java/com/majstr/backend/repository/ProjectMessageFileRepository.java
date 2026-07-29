package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectMessageFile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectMessageFileRepository extends JpaRepository<ProjectMessageFile, UUID> {

    long countByMessageId(UUID messageId);

    /**
     * A file by id AND the message it must belong to.
     *
     * <p>Both halves are required. Looking a file up by id alone and checking the message afterwards
     * would answer "wrong object" and "no such file" differently, which tells a prober that some other
     * master's file exists. Here the two are indistinguishable.</p>
     */
    Optional<ProjectMessageFile> findByIdAndMessageId(UUID id, UUID messageId);

    /**
     * Files old enough to warn about: untouched for the retention period and not yet warned.
     *
     * <p>"Untouched" falls back to the upload time, so a file nobody ever opened ages from the day it
     * arrived rather than living forever on a null. The project and its owner are fetch-joined because
     * the warning names the object and goes to that master.</p>
     */
    @Query("""
            SELECT f FROM ProjectMessageFile f
            JOIN FETCH f.message m
            JOIN FETCH m.project p
            JOIN FETCH p.owner
            WHERE f.deletionWarnedAt IS NULL
              AND COALESCE(f.lastOpenedAt, f.createdAt) < :cutoff
            ORDER BY COALESCE(f.lastOpenedAt, f.createdAt)
            """)
    List<ProjectMessageFile> findDueForWarning(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Files whose notice has run out.
     *
     * <p>The second condition is the escape hatch working: opening a warned file clears the warning, so
     * anything still here has genuinely been ignored since the master was told. Checked anyway rather
     * than trusted, because a future caller that forgets to clear it would otherwise delete a file the
     * master had just looked at.</p>
     */
    @Query("""
            SELECT f FROM ProjectMessageFile f
            JOIN FETCH f.message m
            JOIN FETCH m.project p
            JOIN FETCH p.owner
            WHERE f.deletionWarnedAt IS NOT NULL
              AND f.deletionWarnedAt < :cutoff
              AND (f.lastOpenedAt IS NULL OR f.lastOpenedAt < f.deletionWarnedAt)
            ORDER BY f.deletionWarnedAt
            """)
    List<ProjectMessageFile> findDueForDeletion(@Param("cutoff") Instant cutoff, Pageable page);
}
