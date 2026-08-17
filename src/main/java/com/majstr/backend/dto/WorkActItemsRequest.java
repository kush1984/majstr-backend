package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Replace a work act's lines wholesale. {@code line_total} and {@code cumulative_before} are NOT
 * accepted here — the server computes both (line_total = unitPrice × quantity; cumulative_before is
 * frozen from the object's SIGNED acts). {@code estimateItemId} null = an additional work.
 */
public record WorkActItemsRequest(
        @NotNull @Valid List<Line> items
) {
    public record Line(
            UUID estimateItemId,
            UUID estimateId,
            @NotNull ItemType type,
            @NotNull @Size(max = 255) String name,
            @Size(max = 100) String category,
            @NotNull Unit unit,
            @NotNull BigDecimal unitPrice,
            @NotNull @DecimalMin("0.001") BigDecimal quantity
    ) {}
}
