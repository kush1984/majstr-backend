package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The positions the master ticked for his price list — the whole set, replacing the previous one. */
public record PriceListRequest(
        @NotNull @Size(max = 5000) List<@NotNull UUID> itemIds
) {}
