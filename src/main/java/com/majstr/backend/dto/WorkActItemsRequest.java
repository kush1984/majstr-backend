package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Replace a work act's lines wholesale. {@code line_total} and {@code cumulative_before} are NOT
 * accepted here — the server computes both (line_total = unitPrice × quantity; cumulative_before is
 * frozen from the object's SIGNED acts). {@code estimateItemId} null = an additional work.
 * {@code estimateId} is advisory: for an estimate-linked line the server re-derives it from the
 * item itself (review fix), so a mismatched or null value cannot skew the economy aggregates.
 */
public record WorkActItemsRequest(
        @NotNull @Valid List<Line> items
) {
    public record Line(
            UUID estimateItemId,
            UUID estimateId,
            @NotNull ItemType type,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 100) String category,
            @NotNull Unit unit,
            // Zero allowed (a free line, e.g. warranty work); negative is not — a negative line
            // would silently shrink «Прийнято актами» and the signed PDF's totals.
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
            @NotNull @DecimalMin("0.001") @Digits(integer = 12, fraction = 3) BigDecimal quantity
    ) {}
}
