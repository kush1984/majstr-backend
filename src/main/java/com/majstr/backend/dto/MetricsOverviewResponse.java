package com.majstr.backend.dto;

import com.majstr.backend.entity.Plan;

import java.math.BigDecimal;
import java.util.Map;

public record MetricsOverviewResponse(
        long usersTotal,
        long usersNewToday,
        long usersNewThisWeek,
        long usersNewThisMonth,
        long usersActive30d,
        Map<Plan, Long> planDistribution,
        BigDecimal conversionRatePercent,
        ChurnSummary churn
) {
    public record ChurnSummary(
            long activeLastMonth,
            long stillActiveThisMonth,
            long churned
    ) {}
}
