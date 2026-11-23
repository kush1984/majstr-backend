package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Add several catalog positions to an estimate in one request (multi-select in the
 * "from catalog" picker). Each entry mirrors the single from-catalog add (catalog
 * item id + quantity + optional sort order); the server copies price/unit/type/
 * category from the catalog item, in one transaction.
 */
public record AddCatalogItemsBatchRequest(
        @NotEmpty @Valid List<Entry> items
) {
    public record Entry(
            @NotNull UUID catalogItemId,
            @NotNull @DecimalMin(value = "0.001", message = "quantity must be greater than 0")
            @Digits(integer = 12, fraction = 3) BigDecimal quantity,
            @PositiveOrZero Integer sortOrder,
            /**
             * Optional CLIENT-generated id for the line this entry creates — the batch
             * equivalent of the {@code X-Entity-Uuid} header, which cannot carry N ids.
             * Makes a replayed offline batch idempotent per line instead of duplicating the
             * whole selection. Null for an ordinary online add.
             */
            UUID id
    ) {
        /** Online callers that don't author offline. */
        public Entry(UUID catalogItemId, BigDecimal quantity, Integer sortOrder) {
            this(catalogItemId, quantity, sortOrder, null);
        }
    }
}
