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
        Instant createdAt
) {
    public static CatalogItemResponse from(CatalogItem item) {
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
                item.getCreatedAt()
        );
    }
}
