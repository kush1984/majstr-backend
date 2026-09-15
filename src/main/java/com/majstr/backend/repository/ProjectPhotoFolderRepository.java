package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectPhotoFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPhotoFolderRepository extends JpaRepository<ProjectPhotoFolder, UUID> {

    List<ProjectPhotoFolder> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /** Case-insensitive on purpose: «Фасад» and «фасад» are ONE folder (V133's unique index is on
     *  {@code lower(btrim(name))}), and the row that comes back owns the spelling photos carry. */
    Optional<ProjectPhotoFolder> findByProjectIdAndNameIgnoreCase(UUID projectId, String name);

    Optional<ProjectPhotoFolder> findByIdAndProjectId(UUID id, UUID projectId);
}
