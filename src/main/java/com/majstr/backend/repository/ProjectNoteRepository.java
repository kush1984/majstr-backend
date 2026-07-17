package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectNoteRepository extends JpaRepository<ProjectNote, UUID> {

    /** The object's notes, newest first (the latest note is usually the most relevant). */
    List<ProjectNote> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Owner-scoped load for edit/delete — the service also checks the object is owned. */
    Optional<ProjectNote> findByIdAndProjectId(UUID id, UUID projectId);
}
