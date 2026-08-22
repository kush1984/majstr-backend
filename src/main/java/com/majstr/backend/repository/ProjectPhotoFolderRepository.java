package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectPhotoFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectPhotoFolderRepository extends JpaRepository<ProjectPhotoFolder, UUID> {

    List<ProjectPhotoFolder> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    Optional<ProjectPhotoFolder> findByProjectIdAndName(UUID projectId, String name);

    Optional<ProjectPhotoFolder> findByIdAndProjectId(UUID id, UUID projectId);
}
