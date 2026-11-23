package com.majstr.backend.dto;

import com.majstr.backend.entity.Region;
import jakarta.validation.constraints.NotNull;

/** Where the master works (V219). */
public record RegionRequest(@NotNull Region region) {}
