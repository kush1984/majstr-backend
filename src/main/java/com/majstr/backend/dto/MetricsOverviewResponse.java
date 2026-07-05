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
        ChurnSummary churn,
        // Masters with subscription auto-renew on — an MRR/retention signal.
        long autoRenewUsers
) {
    public record ChurnSummary(
            long activeLastMonth,
            long stillActiveThisMonth,
            long churned
    ) {}
}
