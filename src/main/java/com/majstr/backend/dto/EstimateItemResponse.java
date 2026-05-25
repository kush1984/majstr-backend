package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public record EstimateItemResponse(
        UUID id,
        ItemType type,
        String name,
        Unit unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int sortOrder
) {
    public static EstimateItemResponse from(EstimateItem item) {
        BigDecimal lineTotal = item.getQuantity()
                .multiply(item.getUnitPrice())
                .setScale(2, RoundingMode.HALF_UP);
        return new EstimateItemResponse(
                item.getId(),
                item.getType(),
                item.getName(),
                item.getUnit(),
                item.getQuantity(),
                item.getUnitPrice(),
                lineTotal,
                item.getSortOrder()
        );
    }
}
