package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * The full set of estimates that should show on the object's ECONOMY portal — every id must
 * already be SIGNED (rejected otherwise, see {@code ProjectPortalService.updateEconomy}; the
 * ECONOMY portal is a settled-money view, never a place to sign something). An empty list is
 * legal (economy portal shows none). {@code paymentsVisible} is the object-level toggle for the
 * compact payments card — off by default, sent explicitly every publish.
 */
public record EconomyUpdateRequest(
        @NotNull List<UUID> estimateIds,
        @NotNull Boolean paymentsVisible
) {}
