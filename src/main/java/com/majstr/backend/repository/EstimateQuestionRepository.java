package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateQuestionRepository extends JpaRepository<EstimateQuestion, UUID> {

    List<EstimateQuestion> findByEstimateIdOrderByCreatedAtAsc(UUID estimateId);

    /** All questions across every estimate of a project, newest first. */
    List<EstimateQuestion> findByEstimateProjectIdOrderByCreatedAtDesc(UUID projectId);

    /** Unread questions on a single project. */
    long countByEstimateProjectIdAndReadFalse(UUID projectId);

    /** All of the owner's unread questions (across every project) — dashboard badge. */
    long countByEstimateProjectOwnerIdAndReadFalse(UUID ownerId);

    /**
     * Unread-question count per project for a set of projects. One grouped query
     * (no N+1); projects with no unread questions are simply absent from the result.
     * Returns rows of {@code [project_id (UUID), count (Long)]}.
     */
    @Query("""
            SELECT q.estimate.project.id, COUNT(q)
            FROM EstimateQuestion q
            WHERE q.estimate.project.id IN :projectIds AND q.read = false
            GROUP BY q.estimate.project.id
            """)
    List<Object[]> countUnreadByProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}
