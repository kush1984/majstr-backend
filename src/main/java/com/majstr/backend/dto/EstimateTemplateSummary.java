package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;

import java.util.UUID;

/**
 * One row in the template picker / "My templates" list. {@code isDefault}
 * separates the 88 system templates from the master's own; {@code itemCount}
 * is the "N позицій" hint (filled from a single grouped count, no N+1).
 *
 * <p>{@code customTradeId}/{@code customTradeName} are set only for a master's OWN template
 * filed under a master-invented trade — always {@code null} for a system default.</p>
 *
 * <p>{@code description} is the bundle's client-facing explanation (a finish level and its
 * tolerances, say) — null for the vast majority of bundles, which are a plain list of works.
 * Shown behind an (i) in the picker, never inline: it is a paragraph, not a subtitle.</p>
 */
public record EstimateTemplateSummary(
        UUID id,
        String name,
        String description,
        Trade trade,
        UUID customTradeId,
        String customTradeName,
        boolean isDefault,
        int itemCount
) {}
