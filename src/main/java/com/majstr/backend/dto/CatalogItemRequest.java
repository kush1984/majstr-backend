package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogItemRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String category,
        /** Optional — which trade this position belongs to (for the catalog filter). Ignored
         *  (forced to OTHER) when {@code customTradeId} is set. */
        Trade trade,
        /** Optional — a master-invented trade instead of one of the above. Must belong to the
         *  caller (validated in {@code CatalogService}). */
        UUID customTradeId,
        @NotNull ItemType type,
        @NotNull Unit unit,
        /** A price; ignored (stored as 0) on a «%» position, which has none — see {@link #isPriceUsable()}. */
        @NotNull @PositiveOrZero
        @Digits(integer = 13, fraction = 2) BigDecimal defaultPrice,
        /**
         * The master's own comment (V207). <b>Three-valued</b>, as on an estimate line: absent (null)
         * leaves it alone — an inline price edit or an older build's queue must not wipe it; blank
         * clears it; text sets it. Either way the position's library hint ({@code description}) goes.
         */
        @Size(max = 500) String note
) {

    /** Every caller that does not touch the comment — it stays as it is. */
    public CatalogItemRequest(String name, String category, Trade trade, UUID customTradeId, ItemType type,
                              Unit unit, BigDecimal defaultPrice) {
        this(name, category, trade, customTradeId, type, unit, defaultPrice, null);
    }

    /**
     * Every position needs a price except a «%» one: a percentage is a share of a sum that exists
     * only in an estimate, so the catalog holds no figure for it (owner ruling 2026-11-20 — the old
     * «base» in the price column produced «1 % · 1 000 ₴ — база»).
     */
    @AssertTrue(message = "defaultPrice must be greater than 0")
    public boolean isPriceUsable() {
        return unit == Unit.PERCENT || defaultPrice == null || defaultPrice.compareTo(BigDecimal.ZERO) > 0;
    }
}
