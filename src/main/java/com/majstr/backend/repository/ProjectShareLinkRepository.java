package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ShareLinkKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectShareLinkRepository extends JpaRepository<ProjectShareLink, UUID> {

    /**
     * By token AND kind. Never look one up by token alone: the two kinds share this table, and
     * resolving a MESSAGE token as a portal would show a supplier the client's prices.
     */
    Optional<ProjectShareLink> findByTokenAndKind(String token, ShareLinkKind kind);

    Optional<ProjectShareLink> findFirstByProjectIdAndKindAndRevokedFalseOrderByCreatedAtDesc(
            UUID projectId, ShareLinkKind kind);
}
