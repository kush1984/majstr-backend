package com.majstr.backend.dto;

import com.majstr.backend.entity.Region;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * How much the oblasts can say about prices yet (V222 + V223, admin only): masters who named the
 * oblast, positions with an oblast median of their own, the country baseline (positions 3+ oblasts
 * price), and the oblast factor against it (null until 3+ of its masters priced such positions).
 */
public record RegionalPriceSummary(
        List<Row> regions,
        long mastersWithoutRegion,
        /** Positions with a country baseline (3+ oblasts) — what any factor is measured on. */
        long baselinePositions,
        /** When the weekly run last wrote, null before the first one. */
        Instant computedAt
) {
    public record Row(
            Region region,
            long masters,
            long positionsWithMedian,
            BigDecimal factor,
            int factorMasters,
            int factorPositions
    ) {}
}
