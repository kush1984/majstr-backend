package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The master-confirmed import (after review-screen edits). Amounts/units/types are
 * final here — the server just dedups and persists. {@code trade} applies to the
 * whole batch (null → OTHER). Per-item {@code policy} overrides {@code defaultPolicy}
 * for a name that already exists in the owner's catalog.
 */
public record CatalogImportCommitRequest(
        @NotEmpty @Size(max = 500) @Valid List<CommitItem> items,
        Trade trade,
        @NotNull DedupPolicy defaultPolicy
) {
    public record CommitItem(
            @NotBlank @Size(max = 255) String name,
            @NotNull Unit unit,
            @NotNull @DecimalMin("0.0") BigDecimal price,
            @NotNull ItemType type,
            /** Null → use the batch {@code defaultPolicy} when this name already exists. */
            DedupPolicy policy
    ) {}

    /** What to do when an item's (name, type) already exists in the owner's catalog. */
    public enum DedupPolicy { UPDATE_PRICE, SKIP }
}
