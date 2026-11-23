package com.majstr.backend.repository;

import com.majstr.backend.entity.AppReview;
import com.majstr.backend.entity.AppReviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppReviewRepository extends JpaRepository<AppReview, UUID> {

    /** The admin's list: unread first, then newest. The author is fetched — every row shows him. */
    @Query("""
            SELECT r FROM AppReview r JOIN FETCH r.user
            ORDER BY CASE WHEN r.status = com.majstr.backend.entity.AppReviewStatus.NEW THEN 0 ELSE 1 END,
                     r.createdAt DESC
            """)
    List<AppReview> findForAdmin(Pageable page);

    /** What majstr.pro shows: published, newest first. */
    @Query("SELECT r FROM AppReview r JOIN FETCH r.user WHERE r.status = :status ORDER BY r.publishedAt DESC")
    List<AppReview> findByStatusWithUser(@Param("status") AppReviewStatus status, Pageable page);

    boolean existsByUserIdAndCreatedAtAfter(UUID userId, Instant after);

    long countByStatus(AppReviewStatus status);

    /** Has he had a success yet — an estimate that left the app or came back signed? */
    @Query("""
            SELECT COUNT(e) > 0 FROM Estimate e
            WHERE e.project.owner.id = :userId AND e.project.sample = false
              AND e.status IN (com.majstr.backend.entity.EstimateStatus.SENT,
                               com.majstr.backend.entity.EstimateStatus.SIGNED)
            """)
    boolean hasSentOrSignedEstimate(@Param("userId") UUID userId);
}
