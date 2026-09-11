package com.majstr.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A partial edit. Every field is optional: {@code null} means "leave it". Changing the quantity
 * stamps {@code edited}, which is what stops the next recalculation from overwriting the master's
 * own number.
 */
public record ShoppingListItemUpdateRequest(
        @DecimalMin(value = "0.001") BigDecimal quantity,
        @Size(max = 500) String note,
        Boolean bought,
        Suggestion suggestion
) {

    /**
     * What the master answered to a parked recalculation figure (V128). Both answers clear the
     * suggestion; they differ only in whose number stays. {@code ACCEPT} also drops {@code edited},
     * because taking our figure hands the row back to the calculator.
     *
     * <p>Applied to a row that carries no suggestion this is a NO-OP, never an error: the screen is
     * used offline, and by the time a queued tap replays, the suggestion may already be gone.</p>
     */
    public enum Suggestion {
        ACCEPT,
        KEEP_MINE
    }
}
