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
        // Share of masters who have ACTUALLY PAID. Deliberately not (PRO + TEAM) / total: that
        // counts trials and admin grants as revenue and would read the same the day before and the
        // day after the first real payment.
        BigDecimal conversionRatePercent,
        // What those paid plans are really made of — bought vs trial vs admin-granted.
        SubscriptionBreakdown subscriptions,
        ChurnSummary churn,
        // Masters with subscription auto-renew on — an MRR/retention signal.
        long autoRenewUsers,
        // Total master→master referral rewards granted (viral-loop signal).
        long referralRewards
) {
    public record ChurnSummary(
            long activeLastMonth,
            long stillActiveThisMonth,
            long churned
    ) {}
}
