package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
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
 * «Оновити каталог» from an estimate's card: the prices the master ticked, written into his catalog,
 * and the positions he ticked, added to it — one request, one transaction.
 *
 * <p>Only a CLIENT price travels: there is no crew-price field here at all, so an estimate line's
 * {@code source_unit_price} cannot reach the catalog by construction. A price of 0 is refused
 * (as on every catalog door) — the card never offers a line priced at 0.</p>
 */
public record CatalogSyncRequest(
        @NotNull @Size(max = 500) List<@Valid @NotNull PriceUpdate> updates,
        @NotNull @Size(max = 500) List<@Valid @NotNull NewPosition> creates
) {

    /** A position already in the master's catalog takes this price. */
    public record PriceUpdate(
            @NotNull UUID catalogItemId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal defaultPrice
    ) {}

    /**
     * A position the catalog does not have yet. {@code id} is client-generated, so a replay finds the
     * row it already created; a position already under this name + type + unit is updated instead
     * (the catalog's own upsert) — the last write wins.
     */
    public record NewPosition(
            @NotNull UUID id,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 100) String category,
            Trade trade,
            @NotNull ItemType type,
            @NotNull Unit unit,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal defaultPrice
    ) {}
}
