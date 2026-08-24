package com.majstr.backend.repository;

import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // The card DTO reads client id/name per row — fetch the client in the same
    // query instead of one lazy SELECT per project.
    @EntityGraph(attributePaths = "client")
    List<Project> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    // ---- dashboard metrics (aggregate, no entity loading) -----------------

    long countByOwnerIdAndStatus(UUID ownerId, ProjectStatus status);

    long countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(UUID ownerId, ProjectStatus status, Instant since);

    /** Dashboard "Активні" (object-status-unification): objects derived as IN_PROGRESS — not
     *  cancelled, not completed, and with at least one SIGNED estimate. Replaces the old
     *  {@code countByOwnerIdAndStatus(IN_PROGRESS)}, which only counted objects whose stored
     *  status happened to have been set to IN_PROGRESS (by signing) rather than every object that
     *  actually IS in progress by the unified derivation. */
    @Query(value = """
            SELECT COUNT(DISTINCT p.id)
            FROM projects p
            JOIN estimates e ON e.project_id = p.id AND e.status = 'SIGNED'
            WHERE p.owner_id = :ownerId AND p.status <> 'CANCELLED' AND p.completed_at IS NULL
            """, nativeQuery = true)
    long countInProgressStage(@Param("ownerId") UUID ownerId);

    /** Dashboard "Очікує" (object-status-unification): OBJECTS derived as PENDING_SIGNATURE — at
     *  least one SENT estimate, none SIGNED, not cancelled, not completed. Replaces the old
     *  {@code EstimateRepository.countByProjectOwnerIdAndStatus(SENT)}, which counted SENT
     *  ESTIMATES — an object with two SENT variants inflated the count, and once signed an object
     *  dropped out of neither number in sync with the other (the exact "1 vs 0" bug report). */
    @Query(value = """
            SELECT COUNT(DISTINCT p.id)
            FROM projects p
            WHERE p.owner_id = :ownerId AND p.status <> 'CANCELLED' AND p.completed_at IS NULL
                  AND EXISTS (SELECT 1 FROM estimates e WHERE e.project_id = p.id AND e.status = 'SENT')
                  AND NOT EXISTS (SELECT 1 FROM estimates e2 WHERE e2.project_id = p.id AND e2.status = 'SIGNED')
            """, nativeQuery = true)
    long countPendingSignatureStage(@Param("ownerId") UUID ownerId);

    /**
     * Per-project "has a SIGNED estimate" / "has a SENT estimate" flags, batched for the whole
     * owner's list — the two facts {@link com.majstr.backend.entity.ObjectStage#derive} needs
     * beyond what's already on the {@code Project} row itself. One grouped query, no N+1 (mirrors
     * {@code EstimateRepository.findLatestEstimateSummaries}' batching pattern). A project with no
     * estimates at all is simply absent from the result — the caller treats a missing row as
     * {@code false}/{@code false}.
     *
     * <p>Row shape: {@code [project_id (UUID), has_signed (Boolean), has_sent (Boolean)]}.</p>
     */
    @Query(value = """
            SELECT e.project_id,
                   BOOL_OR(e.status = 'SIGNED') AS has_signed,
                   BOOL_OR(e.status = 'SENT') AS has_sent
            FROM estimates e
            WHERE e.project_id IN (:projectIds)
            GROUP BY e.project_id
            """, nativeQuery = true)
    List<Object[]> findStageFlags(@Param("projectIds") Collection<UUID> projectIds);

    // ---- admin activity ---------------------------------------------------

    /** Project count per owner for a set of users (admin list, no N+1). */
    @Query("SELECT p.owner.id AS ownerId, COUNT(p) AS cnt FROM Project p "
            + "WHERE p.owner.id IN :ownerIds GROUP BY p.owner.id")
    List<OwnerCount> countByOwnerIdIn(@Param("ownerIds") Collection<UUID> ownerIds);

    /**
     * How many distinct masters have created at least one project (funnel step).
     *
     * <p>{@code role = USER} matches {@code countActivatedOwnersBySource} and
     * {@code UserRepository.countUsersBySource}: the funnel is "across masters", and one demo object
     * on an admin account would otherwise make the by-source rows stop summing to the funnel.</p>
     */
    @Query("""
            SELECT COUNT(DISTINCT p.owner.id) FROM Project p
            WHERE p.owner.role = com.majstr.backend.entity.Role.USER
            """)
    long countDistinctOwners();

    /** Lifetime estimate counters — bumped on estimate create/delete. Bulk UPDATE
     *  (not read-modify-write) so concurrent creates can't lose a count. */
    @Modifying
    @Query("UPDATE Project p SET p.estimatesCreated = p.estimatesCreated + 1 WHERE p.id = :id")
    void incrementEstimatesCreated(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Project p SET p.estimatesDeleted = p.estimatesDeleted + 1 WHERE p.id = :id")
    void incrementEstimatesDeleted(@Param("id") UUID id);

    /** Per-object estimate churn for the admin user detail (name + lifetime
     *  created/deleted), newest object first. */
    @Query("""
            SELECT p.name AS name, p.estimatesCreated AS created, p.estimatesDeleted AS deleted
            FROM Project p
            WHERE p.owner.id = :ownerId
            ORDER BY p.createdAt DESC
            """)
    List<ProjectEstimateStat> findEstimateStatsByOwner(@Param("ownerId") UUID ownerId);

    /** Projection for {@link #findEstimateStatsByOwner}. */
    interface ProjectEstimateStat {
        String getName();
        int getCreated();
        int getDeleted();
    }

    /** "Activated" (has ≥1 object) masters grouped by referral source — admin
     *  by-source report. One grouped query, no N+1. */
    @Query("""
            SELECT p.owner.referralSource AS source, COUNT(DISTINCT p.owner.id) AS cnt
            FROM Project p
            WHERE p.owner.role = com.majstr.backend.entity.Role.USER
            GROUP BY p.owner.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countActivatedOwnersBySource();
}
