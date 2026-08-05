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
        ItemType type,
        Unit unit,
        BigDecimal defaultPrice,
        /** The master's own arrangement within his catalog (V87) — what the client renders by. */
        int sortOrder,
        Instant createdAt
) {
    public static CatalogItemResponse from(CatalogItem item) {
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getCategory(),
                item.getTrade(),
                item.getType(),
                item.getUnit(),
                item.getDefaultPrice(),
                item.getSortOrder(),
                item.getCreatedAt()
        );
    }
}
