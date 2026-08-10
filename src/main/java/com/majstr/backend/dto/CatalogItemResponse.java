package com.majstr.backend.dto;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CatalogItemResponse(
        UUID id,
        String name,
        String category,
        Trade trade,
        /** Set only when this position is filed under a master-invented trade — {@code trade} is
         *  then always OTHER. Denormalized alongside its id so the client never needs a separate
         *  lookup; it always reflects the CURRENT name (a live FK, not a snapshot). */
        UUID customTradeId,
        String customTradeName,
        ItemType type,
        Unit unit,
        BigDecimal defaultPrice,
        /** The master's own arrangement within his catalog (V87) — what the client renders by. */
        int sortOrder,
        Instant createdAt,
        /** Other trades that ALSO recognize this exact (name, type, unit) per {@code
         *  catalog_templates} — never includes {@link #trade} itself. {@code catalog_items} has
         *  one row per (owner, name, type, unit); a master running two trades that happen to
         *  share identical wording (V99: PAINTER's organizational services duplicate several of
         *  TILING's) only ever owns ONE row, tagged whichever trade claimed it first. The client's
         *  trade filter chips use this so that row still shows up under the OTHER trade's chip too
         *  — see {@code TradeFilterChips.tradeMatches}. Empty for anything not (also) shipped
         *  under a second trade. */
        List<Trade> sharedTrades
) {
    public static CatalogItemResponse from(CatalogItem item) {
        return from(item, List.of());
    }

    public static CatalogItemResponse from(CatalogItem item, List<Trade> sharedTrades) {
        var customTrade = item.getCustomTrade();
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getCategory(),
                item.getTrade(),
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                item.getType(),
                item.getUnit(),
                item.getDefaultPrice(),
                item.getSortOrder(),
                item.getCreatedAt(),
                sharedTrades
        );
    }
}
