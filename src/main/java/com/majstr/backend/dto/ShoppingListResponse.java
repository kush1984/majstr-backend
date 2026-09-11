package com.majstr.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The whole list of one object. {@code id} and {@code archivedAt} are null until the first row is
 * added — a list is created lazily, so an object with nothing to buy answers 200 with an empty
 * list rather than 404.
 *
 * <p>{@code sourceEstimateUnsigned} is a HINT, not a gate: at least one row was calculated from an
 * estimate the client has not signed, so its quantities can still move. Nothing is blocked by it —
 * a master buys before the signature all the time, and he is told so rather than stopped.</p>
 */
public record ShoppingListResponse(
        UUID id,
        UUID projectId,
        String projectName,
        Instant archivedAt,
        int totalCount,
        int boughtCount,
        boolean sourceEstimateUnsigned,
        List<ShoppingListItemResponse> items
) {
    public static ShoppingListResponse empty(UUID projectId, String projectName) {
        return new ShoppingListResponse(null, projectId, projectName, null, 0, 0, false, List.of());
    }
}
