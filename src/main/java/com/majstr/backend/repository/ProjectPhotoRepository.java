package com.majstr.backend.repository;

import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.ProjectPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPhotoRepository extends JpaRepository<ProjectPhoto, UUID> {

    List<ProjectPhoto> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    long countByProjectIdAndSource(UUID projectId, PhotoSource source);

    List<ProjectPhoto> findByProjectIdAndVisibilityOrderByCreatedAtDesc(UUID projectId, PhotoVisibility visibility);

    Optional<ProjectPhoto> findByIdAndProjectId(UUID id, UUID projectId);

    /** Folder-delete guard (photo-folders): a folder may go only when no photo carries its name. */
    boolean existsByProjectIdAndFolder(UUID projectId, String folder);
}
