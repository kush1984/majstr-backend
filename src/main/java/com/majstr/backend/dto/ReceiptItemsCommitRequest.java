package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The master-confirmed receipt lines (after review-screen edits), appended to an open
 * estimate. Unlike the estimate import, receipts never touch the catalog and carry no
 * deposit — just the line items. Amounts/units/types are final; the server persists them
 * onto the estimate (SIGNED → 409).
 */
public record ReceiptItemsCommitRequest(
        @NotEmpty @Size(max = 200) @Valid List<CommitItem> items
) {
    public record CommitItem(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
            @NotNull @DecimalMin("0.0") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
            @NotNull ItemType type,
            @Size(max = 100) String category
    ) {}
}
