package com.majstr.backend.repository;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateRepository extends JpaRepository<Estimate, UUID> {

    List<Estimate> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** All estimates of a project, any status — the live count for the FREE
     *  per-project estimate limit (deleting one frees a slot). */
    long countByProjectId(UUID projectId);

    long countByProjectOwnerIdAndStatus(UUID ownerId, EstimateStatus status);

    // ---- admin activity ---------------------------------------------------

    /** Estimate count per owner for a set of users (admin list, no N+1). */
    @Query("SELECT e.project.owner.id AS ownerId, COUNT(e) AS cnt FROM Estimate e "
            + "WHERE e.project.owner.id IN :ownerIds GROUP BY e.project.owner.id")
    List<OwnerCount> countByProjectOwnerIdIn(@Param("ownerIds") Collection<UUID> ownerIds);

    /** Estimate count per owner filtered by status (e.g. SIGNED) for the admin list. */
    @Query("SELECT e.project.owner.id AS ownerId, COUNT(e) AS cnt FROM Estimate e "
            + "WHERE e.project.owner.id IN :ownerIds AND e.status = :status GROUP BY e.project.owner.id")
    List<OwnerCount> countByProjectOwnerIdInAndStatus(@Param("ownerIds") Collection<UUID> ownerIds,
                                                      @Param("status") EstimateStatus status);

    /** Per-status estimate counts for one owner (admin user detail). Rows: [status, count]. */
    @Query("SELECT e.status, COUNT(e) FROM Estimate e WHERE e.project.owner.id = :ownerId GROUP BY e.status")
    List<Object[]> countByStatusForOwner(@Param("ownerId") UUID ownerId);

    /** When the owner last created an estimate (admin user detail; null if none). */
    @Query("SELECT MAX(e.createdAt) FROM Estimate e WHERE e.project.owner.id = :ownerId")
    Instant findLastEstimateCreatedAt(@Param("ownerId") UUID ownerId);

    /** Distinct masters with at least one estimate / one signed estimate (funnel). */
    @Query("SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e")
    long countDistinctProjectOwners();

    @Query("SELECT COUNT(DISTINCT e.project.owner.id) FROM Estimate e WHERE e.status = :status")
    long countDistinctProjectOwnersByStatus(@Param("status") EstimateStatus status);

    /**
     * For each given project, the latest estimate (by createdAt) with its status
     * and total. The total sums each line rounded to kopiykas (HALF_UP, matching
     * EstimateService), so subtotals always add up. Projects without an estimate
     * are simply absent from the result. One query for the whole list — no N+1.
     *
     * <p>Returns rows of {@code [project_id (UUID), status (String), total (BigDecimal)]}.
     * Postgres-specific (DISTINCT ON); callers must pass a non-empty collection.</p>
     */
    @Query(value = """
            SELECT le.project_id, le.status,
                   COALESCE(SUM(ROUND(i.quantity * i.unit_price, 2)), 0) AS total
            FROM (
                SELECT DISTINCT ON (e.project_id) e.id, e.project_id, e.status
                FROM estimates e
                WHERE e.project_id IN (:projectIds)
                ORDER BY e.project_id, e.created_at DESC, e.id DESC
            ) le
            LEFT JOIN estimate_items i ON i.estimate_id = le.id
            GROUP BY le.project_id, le.status
            """, nativeQuery = true)
    List<Object[]> findLatestEstimateSummaries(@Param("projectIds") Collection<UUID> projectIds);

    /**
     * Sum of the latest-estimate totals of the owner's projects completed since
     * {@code monthStart}. Completed projects without an estimate contribute 0.
     */
    @Query(value = """
            SELECT COALESCE(SUM(t.total), 0) FROM (
                SELECT le.project_id,
                       COALESCE(SUM(ROUND(i.quantity * i.unit_price, 2)), 0) AS total
                FROM (
                    SELECT DISTINCT ON (e.project_id) e.id, e.project_id
                    FROM estimates e
                    JOIN projects p ON p.id = e.project_id
                    WHERE p.owner_id = :ownerId
                      AND p.status = 'COMPLETED'
                      AND p.completed_at >= :monthStart
                    ORDER BY e.project_id, e.created_at DESC, e.id DESC
                ) le
                LEFT JOIN estimate_items i ON i.estimate_id = le.id
                GROUP BY le.project_id
            ) t
            """, nativeQuery = true)
    BigDecimal sumLatestEstimateTotalForCompletedSince(@Param("ownerId") UUID ownerId,
                                                       @Param("monthStart") Instant monthStart);
}
