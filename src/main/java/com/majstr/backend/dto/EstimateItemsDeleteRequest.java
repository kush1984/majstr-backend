package com.majstr.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The lines to remove in one go.
 *
 * <p>Capped at 500 — comfortably above the largest real estimate («УСІ ПЛИТОЧНІ РОБОТИ» applies
 * 167 positions) and low enough that a malformed client cannot ask the server to load an unbounded
 * list of ids into memory.</p>
 */
public record EstimateItemsDeleteRequest(
        @NotEmpty @Size(max = 500) List<UUID> itemIds
) {}
