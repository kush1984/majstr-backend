package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.MeasurementRefs;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

public record EstimateItemResponse(
        UUID id,
        ItemType type,
        String name,
        String category,
        Unit unit,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int sortOrder,
        /** Measurement elements this line's quantity was summed from (empty = none) —
         *  drives the "Вибрати з замірів" pre-selection. */
        List<UUID> measurementRefs,
        /** True when the master edited the quantity by hand — drives the overwrite warning. */
        boolean quantityManual
) {
    public static EstimateItemResponse from(EstimateItem item) {
        BigDecimal lineTotal = item.getQuantity()
                .multiply(item.getUnitPrice())
                .setScale(2, RoundingMode.HALF_UP);
        return new EstimateItemResponse(
                item.getId(),
                item.getType(),
                item.getName(),
                item.getCategory(),
                item.getUnit(),
                item.getQuantity(),
                item.getUnitPrice(),
                lineTotal,
                item.getSortOrder(),
                MeasurementRefs.parse(item.getMeasurementRefs()),
                item.isQuantityManual()
        );
    }
}
