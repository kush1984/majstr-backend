package com.majstr.backend.dto;

import java.math.BigDecimal;

/**
 * Home-screen metrics for the current contractor. All counts and sums are
 * computed on the backend with aggregate queries.
 */
public record DashboardMetricsResponse(
        long activeProjects,
        long pendingEstimates,
        CompletedThisMonth completedThisMonth
) {
    public record CompletedThisMonth(long count, BigDecimal totalAmount) {}
}
