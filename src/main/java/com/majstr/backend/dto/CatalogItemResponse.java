package com.majstr.backend.dto;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CatalogItemResponse(
        UUID id,
        String name,
        ItemType type,
        Unit unit,
        BigDecimal defaultPrice,
        Instant createdAt
) {
    public static CatalogItemResponse from(CatalogItem item) {
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getType(),
                item.getUnit(),
                item.getDefaultPrice(),
                item.getCreatedAt()
        );
    }
}
