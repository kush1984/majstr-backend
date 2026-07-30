package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the admin catalog-insight screens show. Read-only views over aggregated master data —
 * see {@code AdminCatalogInsightsService} for what each list means and why.
 */
public final class CatalogInsights {

    private CatalogInsights() {
    }

    /**
     * A position masters created that our defaults do not cover — a gap.
     *
     * <p>{@code masters} is the signal that matters: a position twenty people independently
     * typed is a far better default candidate than one somebody added yesterday.
     *
     * <p>Prices are an aggregate ACROSS accounts and are shown, never published. A wide
     * {@code minPrice}–{@code maxPrice} spread is not noise to average away: it usually means
     * the position is ambiguously defined (unclear unit, unclear scope), which is a finding
     * about our catalog, not about the price.
     */
    public record NewPosition(
            String nameKey,
            String name,
            ItemType type,
            Unit unit,
            String category,
            long masters,
            Instant firstSeen,
            BigDecimal medianPrice,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {}

    /**
     * A position our defaults DO cover, that masters wrote differently anyway.
     *
     * <p>The most actionable list of the three: it is direct evidence that our own wording is
     * unusable — they could not find ours, so they typed their own. Fix the default's name, do
     * not add theirs.
     */
    public record RenamedPosition(
            String nameKey,
            String ourName,
            UUID ourTemplateId,
            String theirName,
            ItemType type,
            Unit unit,
            long masters,
            Instant firstSeen
    ) {}

    /**
     * One of our default positions that no estimate line has ever resembled — dead weight.
     *
     * <p>{@code approximate} is always true and is part of the contract: estimate lines are
     * snapshots with no link back to the catalog, so usage can only be matched by name. Fine
     * for "nobody ever typed anything like this", not a usage statistic.
     */
    public record UnusedDefault(
            String nameKey,
            UUID templateId,
            String name,
            Trade trade,
            String category,
            ItemType type,
            Unit unit,
            BigDecimal suggestedPrice,
            boolean approximate
    ) {}

    /** A whole estimate template a master built for themselves. */
    public record NewTemplate(
            String nameKey,
            UUID templateId,
            String name,
            Trade trade,
            int itemCount,
            String ownerEmail,
            Instant createdAt,
            List<String> itemNames
    ) {}
}
