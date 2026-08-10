package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * Home-screen metrics for the current contractor. All counts and sums are
 * computed on the backend with aggregate queries.
 *
 * <p>{@code activeProjects}/{@code pendingObjects} count OBJECTS in the corresponding derived
 * {@link com.majstr.backend.entity.ObjectStage} (object-status-unification) — renamed from
 * {@code pendingEstimates}, which used to count SENT ESTIMATES rather than objects and could
 * disagree with the object-list filter's own count for the same reason.</p>
 */
public record DashboardMetricsResponse(
        long activeProjects,
        long pendingObjects,
        // Total unread client questions across all the contractor's projects.
        long unreadQuestions,
        CompletedThisMonth completedThisMonth
) {
    public record CompletedThisMonth(long count, BigDecimal totalAmount) {}
}
