package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * Home-screen metrics for the current contractor. All counts and sums are
 * computed on the backend with aggregate queries.
 */
public record DashboardMetricsResponse(
        long activeProjects,
        long pendingEstimates,
        // Total unread client questions across all the contractor's projects.
        long unreadQuestions,
        CompletedThisMonth completedThisMonth
) {
    public record CompletedThisMonth(long count, BigDecimal totalAmount) {}
}
