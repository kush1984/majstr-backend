package com.majstr.backend.dto;

import com.majstr.backend.entity.EstimateTemplate;
import com.majstr.backend.entity.EstimateTemplateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.util.List;
import java.util.UUID;

/**
 * A template with its positions — for the "preview composition" view. Items carry
 * no quantity/price (those are resolved when the template is applied).
 */
public record EstimateTemplateDetail(
        UUID id,
        String name,
        Trade trade,
        boolean isDefault,
        List<Item> items
) {
    public record Item(UUID id, String name, ItemType type, Unit unit, int sortOrder) {
        static Item from(EstimateTemplateItem i) {
            return new Item(i.getId(), i.getName(), i.getType(), i.getUnit(), i.getSortOrder());
        }
    }

    public static EstimateTemplateDetail from(EstimateTemplate t, List<EstimateTemplateItem> items) {
        return new EstimateTemplateDetail(
                t.getId(),
                t.getName(),
                t.getTrade(),
                t.isDefault(),
                items.stream().map(Item::from).toList()
        );
    }
}
