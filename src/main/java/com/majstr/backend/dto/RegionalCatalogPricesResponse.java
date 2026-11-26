package com.majstr.backend.dto;

import com.majstr.backend.entity.Region;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * «Ціни по областях» (plan-regional-prices.md, step 2): one oblast's prices for the works in the
 * master's own catalog. The PWA joins {@link Row#itemId} to the catalog it already holds — names,
 * trades, folders and his own price come from there, so they are never sent twice.
 *
 * @param region     the oblast asked for
 * @param factor     that oblast's factor against the country (null — too few masters for one)
 * @param computedAt the weekly run the figures come from (null — no run has written anything yet)
 * @param items      one row per catalog work that has at least one figure
 */
public record RegionalCatalogPricesResponse(Region region, BigDecimal factor, Instant computedAt, List<Row> items) {

    /**
     * @param itemId        the master's catalog position
     * @param level         {@code OBLAST} (median of 3+ masters there), {@code OBLAST_ESTIMATE}
     *                      (country × the oblast's factor), or null — no figure for that oblast
     * @param regionalPrice the oblast's figure, null with {@code level}
     * @param mastersCount  masters behind an {@code OBLAST} median, else null
     * @param countryPrice  the country baseline (one vote per oblast), else the library's price
     */
    public record Row(UUID itemId, String level, BigDecimal regionalPrice, Integer mastersCount,
                      BigDecimal countryPrice) {
    }
}
