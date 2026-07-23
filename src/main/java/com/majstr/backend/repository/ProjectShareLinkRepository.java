package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectShareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectShareLinkRepository extends JpaRepository<ProjectShareLink, UUID> {

    Optional<ProjectShareLink> findByToken(String token);

    Optional<ProjectShareLink> findFirstByProjectIdAndRevokedFalseOrderByCreatedAtDesc(UUID projectId);
}
