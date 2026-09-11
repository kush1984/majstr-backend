package com.majstr.backend.dto;

import com.majstr.backend.entity.ShoppingListItem;
import com.majstr.backend.entity.ShoppingListItemSource;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the shopping list. Deliberately carries <b>no price</b>: in the shop the master reads
 * the price tag, not our forecast, and V81 already ruled that a stale guess beside a real number is
 * worse than no guess.
 */
public record ShoppingListItemResponse(
        UUID id,
        UUID materialId,
        String name,
        Unit unit,
        BigDecimal quantity,
        boolean bought,
        Instant boughtAt,
        boolean edited,
        // Parked figure from the last recalculation of an edited row; null = nothing to offer.
        BigDecimal suggestedQuantity,
        // This row carries only the DIFFERENCE: something for the same material is already settled.
        boolean topUp,
        ShoppingListItemSource source,
        UUID sourceEstimateId,
        String note,
        int sortOrder
) {
    public static ShoppingListItemResponse from(ShoppingListItem item, boolean topUp) {
        return new ShoppingListItemResponse(
                item.getId(),
                item.getMaterialId(),
                item.getName(),
                item.getUnit(),
                item.getQuantity(),
                item.isBought(),
                item.getBoughtAt(),
                item.isEdited(),
                item.getSuggestedQuantity(),
                topUp,
                item.getSource(),
                item.getSourceEstimateId(),
                item.getNote(),
                item.getSortOrder());
    }
}
