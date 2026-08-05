package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The arrangement the master wants for his own catalog, stated in full.
 *
 * <p>Deliberately the same shape as {@link EstimateItemsOrderRequest}, because it is the same
 * problem and the client shares one piece of arithmetic between the two screens. Declarative rather
 * than a diff ("move X after Y"): it is idempotent, so the offline outbox can replay it any number
 * of times and land in the same place — a diff replayed twice moves a position twice.</p>
 *
 * <p>Each line carries its category, so dragging a position into another group is ONE operation:
 * reordering and re-categorising cannot half-apply and leave a position filed under a heading it
 * does not belong to. Categories have no rows of their own — a group IS the run of positions sharing
 * a category, ordered by the first of them — so dragging a whole group needs nothing extra here.</p>
 *
 * <p>Position comes from the index in the list, not from a number the client computes: nothing to
 * keep in sync, and no room for two positions to claim the same slot.</p>
 */
public record CatalogItemsOrderRequest(
        @NotEmpty @Valid List<Line> items
) {
    public record Line(
            @NotNull UUID id,
            /** null / blank → the "Без категорії" group, same as everywhere else. */
            @Size(max = 100) String category
    ) {}
}
