package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The arrangement the master wants for an estimate's lines, stated in full.
 *
 * <p>Declarative rather than a diff ("move item X after Y"), for two reasons. It is idempotent, so
 * the offline outbox can replay it any number of times and land in the same place — a diff replayed
 * twice moves a line twice. And it carries the category with each line, so dragging a line into
 * another section is ONE operation: reordering and re-categorising cannot half-apply and leave a line
 * sorted into a section it does not belong to.</p>
 *
 * <p>Position comes from the index in the list, not from a number the client computes: nothing to
 * keep in sync, and no room for two lines to claim the same slot.</p>
 *
 * <p>Categories have no rows of their own — a section IS the set of lines sharing a category, and the
 * order of sections follows the first line in each. So dragging a whole section is the same operation
 * as dragging a line, and needs nothing extra on the server.</p>
 */
public record EstimateItemsOrderRequest(
        @NotEmpty @Valid List<Line> items
) {
    public record Line(
            @NotNull UUID id,
            /** null / blank → the "Без категорії" section, same as everywhere else. */
            @Size(max = 100) String category
    ) {}
}
