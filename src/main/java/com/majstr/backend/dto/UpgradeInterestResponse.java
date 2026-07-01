package com.majstr.backend.dto;

import java.util.List;

/**
 * Admin summary of PRO upgrade intent: demand width (distinct clickers), intensity
 * (total clicks), warm-lead count, the by-trigger breakdown, and the leads to call.
 */
public record UpgradeInterestResponse(
        long uniqueClickers,
        long totalClicks,
        long interested,
        List<TriggerBreakdown> byTrigger,
        List<UpgradeLead> leads
) {
    public record TriggerBreakdown(String trigger, long count) {}
}
