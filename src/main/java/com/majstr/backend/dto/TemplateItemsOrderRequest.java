package com.majstr.backend.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * The order the master wants a template's positions in, stated in full — the same declarative
 * shape as {@link EstimateItemsOrderRequest} and for the same reason: it is idempotent, so an
 * offline replay lands in the same place (a "move X after Y" diff replayed twice moves it twice).
 *
 * <p>Position comes from the index in the list. Ids the server does not recognise are ignored, and
 * positions the request does not mention keep their relative order after the ones it does — a
 * stale client can never drop a line out of a bundle by omitting it.</p>
 *
 * <p>Order is not decoration here: a default bundle is a SEQUENCE, what is done after what
 * (see docs/architecture.md → "A default bundle is a SEQUENCE, not a set").</p>
 */
public record TemplateItemsOrderRequest(
        @NotEmpty List<UUID> itemIds
) {}
