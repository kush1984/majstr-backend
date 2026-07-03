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

    @EntityGraph(attributePaths = "client")
    List<Project> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, ProjectStatus status);

    long countByOwnerId(UUID ownerId);

    // ---- dashboard metrics (aggregate, no entity loading) -----------------

    long countByOwnerIdAndStatus(UUID ownerId, ProjectStatus status);

    long countByOwnerIdAndStatusAndCompletedAtGreaterThanEqual(UUID ownerId, ProjectStatus status, Instant since);

    // ---- admin activity ---------------------------------------------------

    /** Project count per owner for a set of users (admin list, no N+1). */
    @Query("SELECT p.owner.id AS ownerId, COUNT(p) AS cnt FROM Project p "
            + "WHERE p.owner.id IN :ownerIds GROUP BY p.owner.id")
    List<OwnerCount> countByOwnerIdIn(@Param("ownerIds") Collection<UUID> ownerIds);

    /** How many distinct masters have created at least one project (funnel step). */
    @Query("SELECT COUNT(DISTINCT p.owner.id) FROM Project p")
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
