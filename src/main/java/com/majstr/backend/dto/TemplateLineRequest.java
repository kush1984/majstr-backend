package com.majstr.backend.dto;

import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One line of a bundle applied OFFLINE, replayed when the connection is back
 * ({@code POST /api/estimates/{id}/items/template-line}).
 *
 * <p>Online, a bundle is applied by the server in one request and its lines are born with quantity
 * 0 — «filled per object». Offline the PWA composes the same lines itself and queued them through
 * the MANUAL door, which rightly refuses a quantity of 0, so every one of them was blocked for good
 * (review P0-5). This is the door those lines take instead: no quantity at all (it is 0, as online),
 * and a price that may be 0 where the master's catalog had no match — exactly what the server's own
 * application produces. The manual door and its validation are untouched.</p>
 */
public record TemplateLineRequest(
        @NotNull ItemType type,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 100) String category,
        @NotNull Unit unit,
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal unitPrice,
        @PositiveOrZero Integer sortOrder
) {

    /** A bundle line is a work or a material; a «%» line is never one (V88 retired it from bundles). */
    @AssertTrue(message = "a template line cannot be a percentage")
    public boolean isNotPercent() {
        return unit != Unit.PERCENT;
    }

    /** The line as the estimate stores it — quantity 0, exactly as the online application leaves it. */
    public EstimateItemRequest toItemRequest() {
        return new EstimateItemRequest(type, name, category, unit, BigDecimal.ZERO, unitPrice, sortOrder,
                null, false, null, null);
    }
}
