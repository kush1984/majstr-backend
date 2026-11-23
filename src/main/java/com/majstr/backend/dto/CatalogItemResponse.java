package com.majstr.backend.dto;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CatalogItemResponse(
        UUID id,
        String name,
        String category,
        /** What the position guarantees, when its name cannot say it (V116 — the Q3/Q3+/Q4
         *  drywall finishing levels). Read-only: {@code CatalogItemRequest} has no matching
         *  field, so an edit from the PWA leaves whatever the library copy carried. */
        String description,
        /** The master's own comment (V207); null = none. */
        String note,
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
         *  last. The client needs this because {@link #sortOrder} is ONE global rank across all his
         *  trades: without it the drywall chip opened on «Каркас і обшивка», because a single
         *  plumbing row filed into that phase outranked every drywall row on the board. */
        Integer categoryOrder,
        Instant createdAt,
        /** A position we shipped for a trade the master has since taken off his profile: kept with
         *  his price (a trade put back shows it again), priced from on every apply, but left off the
         *  catalog screen and the pickers. Never true for a position he typed or imported. */
        boolean hidden
) {
    public static CatalogItemResponse from(CatalogItem item) {
        return from(item, null, false);
    }

    public static CatalogItemResponse from(CatalogItem item, Integer categoryOrder, boolean hidden) {
        var customTrade = item.getCustomTrade();
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getCategory(),
                item.getDescription(),
                item.getNote(),
                item.getTrade(),
                customTrade != null ? customTrade.getId() : null,
                customTrade != null ? customTrade.getName() : null,
                item.getType(),
                item.getUnit(),
                item.getDefaultPrice(),
                item.getSortOrder(),
                categoryOrder,
                item.getCreatedAt(),
                hidden
        );
    }
}
