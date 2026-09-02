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
        /** What the position guarantees, when its name cannot say it (V116 — the Q3/Q3+/Q4
         *  drywall finishing levels). Read-only: {@code CatalogItemRequest} has no matching
         *  field, so an edit from the PWA leaves whatever the library copy carried. */
        String description,
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
        /** Where the LIBRARY files this position's category within {@link #trade}'s own sequence
         *  ({@code min(catalog_templates.sort_order)} over that trade + category — V118's ranking,
         *  which for DRYWALL is the order the work is actually done in). Null for a category the
         *  library ships nothing for, i.e. a folder the master invented; the client sorts those
         *  last. The client needs this because {@link #sortOrder} is ONE global rank while a row
         *  re-filed by {@link #sharedTrades} carries the rank of the trade it is STORED under:
         *  without it the drywall chip opened on «Каркас і обшивка», because a single plumbing row
         *  re-filed into that phase outranked every drywall row on the board. */
        Integer categoryOrder,
        Instant createdAt,
        /** Other trades that ALSO recognize this exact (name, type, unit) per {@code
         *  catalog_templates}, each with the category IT files the position under — never includes
         *  {@link #trade} itself. {@code catalog_items} has one row per (owner, name, type, unit);
         *  a master running two trades that happen to share identical wording (V99: PAINTER's
         *  organizational services duplicate several of TILING's) only ever owns ONE row, tagged
         *  whichever trade claimed it first. The client's trade filter chips use this so that row
         *  still shows up under the OTHER trade's chip too — see {@code
         *  TradeFilterChips.tradeMatches} — and group it under that trade's category while its
         *  chip is the one selected. Empty for anything not (also) shipped under a second trade. */
        List<SharedTrade> sharedTrades
) {
    /** One foreign trade that ships this exact position, where that trade keeps it, and where that
     *  category sits in that trade's sequence — see {@link #categoryOrder}. */
    public record SharedTrade(Trade trade, String category, Integer categoryOrder) {
    }

    public static CatalogItemResponse from(CatalogItem item) {
        return from(item, List.of(), null);
    }

    public static CatalogItemResponse from(CatalogItem item, List<SharedTrade> sharedTrades,
                                           Integer categoryOrder) {
        var customTrade = item.getCustomTrade();
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getCategory(),
                item.getDescription(),
                item.getTrade(),
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                item.getType(),
                item.getUnit(),
                item.getDefaultPrice(),
                item.getSortOrder(),
                categoryOrder,
                item.getCreatedAt(),
                sharedTrades
        );
    }
}
