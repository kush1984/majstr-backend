package com.majstr.backend.dto;

import com.majstr.backend.entity.CatalogTemplate;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/** One default catalog position in the admin editor (carries {@code addedInVersion}
 *  so the admin can see which catalog version introduced it). */
public record AdminCatalogTemplateResponse(
        UUID id,
        Trade trade,
        String category,
        String name,
        ItemType type,
        Unit unit,
        BigDecimal suggestedPrice,
        int addedInVersion
) {
    public static AdminCatalogTemplateResponse from(CatalogTemplate t) {
        return new AdminCatalogTemplateResponse(
                t.getId(), t.getTrade(), t.getCategory(), t.getName(),
                t.getType(), t.getUnit(), t.getSuggestedPrice(), t.getAddedInVersion());
    }
}
