package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The full set of estimates that should be visible on the object's portal —
 * everything else is hidden. An empty list is legal (portal shows none).
 */
public record PortalUpdateRequest(
        @NotNull List<UUID> estimateIds
) {}
