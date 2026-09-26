package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActLineKind;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One frozen line of a work act. {@code exceedsEstimate} is computed live at response time
 * (cumulativeBefore + quantity > the estimate line's CURRENT quantity) — the master's call, not
 * the server's, to convert the excess into additional works. {@code null} estimateItemId = an
 * additional work; it can never "exceed" anything, so the flag is false there.
 *
 * <p>{@code lineKind} says what the line IS (B-55). An ADJUSTMENT is server-authored — the editor
 * renders it read-only and must never echo it back on {@code PUT /items}, or the estimate's own
 * discount would return as an off-estimate work and be billed a second time.</p>
 */
public record WorkActItemResponse(
        UUID id,
        WorkActLineKind lineKind,
        UUID estimateItemId,
        UUID estimateId,
        ItemType type,
        String name,
        String category,
        Unit unit,
        BigDecimal unitPrice,
        BigDecimal quantity,
        BigDecimal lineTotal,
        BigDecimal cumulativeBefore,
        boolean exceedsEstimate,
        int sortOrder
) {
    public static WorkActItemResponse from(WorkActItem item, boolean exceedsEstimate) {
        return new WorkActItemResponse(
                item.getId(),
                item.getLineKind(),
                item.getEstimateItemId(),
                item.getEstimateId(),
                item.getType(),
                item.getName(),
                item.getCategory(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLineTotal(),
                item.getCumulativeBefore(),
                exceedsEstimate,
                item.getSortOrder()
        );
    }
}
