package com.majstr.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Fold several of an object's estimates into one new DRAFT estimate. All line items
 * from the picked estimates are copied (works + materials); the master tidies quantities
 * in the editor. {@code name} is optional (defaults to «Зведений кошторис»).
 */
public record EstimateConsolidateRequest(
        @Size(max = 255) String name,
        @NotEmpty List<UUID> estimateIds
) {}
