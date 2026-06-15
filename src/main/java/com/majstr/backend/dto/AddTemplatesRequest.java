package com.majstr.backend.dto;

import com.majstr.backend.entity.Trade;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Request to merge the starter set of specific trades into the contractor's
 * catalog (used after a trade is added to the profile). Only the user's own
 * trades are honoured; the merge never overwrites or duplicates existing items.
 */
public record AddTemplatesRequest(
        @NotEmpty Set<Trade> trades
) {}
