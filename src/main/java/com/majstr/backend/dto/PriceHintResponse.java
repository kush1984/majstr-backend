package com.majstr.backend.dto;

import com.majstr.backend.entity.Region;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * «Ринок: ~350 ₴/м²» under the price field (plan-regional-prices.md, step 4).
 *
 * @param level        {@code OBLAST} — the median of 3+ masters of the master's oblast;
 *                     {@code OBLAST_ESTIMATE} — the country baseline × the oblast's factor («орієнтовно»);
 *                     {@code COUNTRY} — the library's market-checked price (V176, V224)
 * @param price        the figure to show
 * @param mastersCount masters behind an {@code OBLAST} median, else null
 * @param region       the oblast the figure is for, null for {@code COUNTRY}
 * @param computedAt   the weekly run's time for the two regional levels, null for {@code COUNTRY}
 */
public record PriceHintResponse(String level, BigDecimal price, Integer mastersCount, Region region,
                                Instant computedAt) {
}
