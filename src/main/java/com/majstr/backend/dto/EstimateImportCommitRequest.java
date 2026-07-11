package com.majstr.backend.dto;

import com.majstr.backend.dto.CatalogImportCommitRequest.DedupPolicy;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The master-confirmed estimate import (after review-screen edits). Creates a new
 * estimate on {@code projectId} and — for the rows the master ticked ({@code toCatalog})
 * — upserts them into the master's catalog. Amounts/units/types are final here; the
 * server just persists. Per-item {@code catalogPolicy} decides what happens when a
 * ticked name already exists in the catalog (the review screen surfaces the conflict).
 */
public record EstimateImportCommitRequest(
        @NotNull UUID projectId,
        @Size(max = 255) String estimateName,
        @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal depositAmount,
        @NotEmpty @Size(max = 500) @Valid List<CommitItem> items
) {
    public record CommitItem(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") @Digits(integer = 12, fraction = 3) BigDecimal quantity,
            @NotNull @DecimalMin("0.0") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
            @NotNull ItemType type,
            @Size(max = 100) String category,
            /** Also add/keep this position in the master's catalog. */
            boolean toCatalog,
            /** When {@code toCatalog} and the name already exists: UPDATE_PRICE or SKIP
             *  (null → SKIP). Ignored when {@code toCatalog} is false. */
            DedupPolicy catalogPolicy
    ) {}
}
