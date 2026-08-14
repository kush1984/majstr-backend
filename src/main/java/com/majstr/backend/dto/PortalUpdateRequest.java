package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The full set of estimates that should show on the object's SIGNATURE portal (any status) —
 * everything else is hidden. An empty list is legal (portal shows none). No payments field —
 * the SIGNATURE portal never shows money beyond the estimate itself; see {@link EconomyUpdateRequest}
 * for the object-level payments toggle, which now lives only on the ECONOMY link.
 */
public record PortalUpdateRequest(
        @NotNull List<UUID> estimateIds
) {}
