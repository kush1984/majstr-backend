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
