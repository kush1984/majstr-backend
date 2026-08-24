package com.majstr.backend.repository;

import com.majstr.backend.dto.OwnerSource;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ShareLinkKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /** An ACT link is keyed by its act, not by (project, kind) — one link per act. */
    Optional<ProjectShareLink> findFirstByWorkActIdAndRevokedFalseOrderByCreatedAtDesc(UUID workActId);

    // ---- admin activity ---------------------------------------------------

    /**
     * Masters who ever published an object-level link ({@code ?p=} / {@code ?e=} / {@code ?a=}) —
     * ONE HALF of the funnel's {@code shared} step; the other half is
     * {@link EstimateShareLinkRepository#findSharedOwners}. The caller unions the two id sets
     * rather than summing two counts, because a master usually has both kinds of link and a sum
     * would count them twice.
     *
     * <p>{@code kinds} is always {@link ShareLinkKind#SHARED_WITH_CLIENT} — MESSAGE never counts.</p>
     *
     * <p><b>Note the missing {@code AndRevokedFalse}.</b> Two of the three lookups above carry it and
     * copying it here is the obvious mistake: this query answers "ever shared", not "can still be
     * opened". With the filter the two halves of the union would mean different things and the funnel
     * step would shrink whenever a master revoked a link.</p>
     */
    @Query("""
            SELECT DISTINCT l.project.owner.id AS ownerId,
                            l.project.owner.referralSource AS source
            FROM ProjectShareLink l
            WHERE l.kind IN :kinds AND l.project.owner.role = com.majstr.backend.entity.Role.USER
            """)
    List<OwnerSource> findSharedOwners(@Param("kinds") Collection<ShareLinkKind> kinds);

    /** Same rule as {@link #findSharedOwners}, for one master (admin user card). */
    boolean existsByProjectOwnerIdAndKindIn(UUID ownerId, Collection<ShareLinkKind> kinds);
}
