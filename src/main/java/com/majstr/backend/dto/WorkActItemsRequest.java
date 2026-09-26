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
 * <p>For a line that names an {@code estimateItemId}, EVERYTHING but the quantity is advisory
 * (B-56): {@code estimateId}, {@code type}, {@code name}, {@code category}, {@code unit} and
 * {@code unitPrice} are read off the estimate item itself, so no request can bill a figure the
 * client never signed or file the line under the wrong estimate. The fields stay on the record
 * because the same record also carries ADDITIONAL (off-estimate) lines, where they ARE the line.</p>
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
