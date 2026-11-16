package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectTaskRepository extends JpaRepository<ProjectTask, UUID> {

    List<ProjectTask> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    Optional<ProjectTask> findByIdAndProjectId(UUID id, UUID projectId);
}
