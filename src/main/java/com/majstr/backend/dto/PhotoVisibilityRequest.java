package com.majstr.backend.dto;

import com.majstr.backend.entity.PhotoVisibility;
import jakarta.validation.constraints.NotNull;

/** Set a MANUAL photo's visibility (show / hide from the client portal). */
public record PhotoVisibilityRequest(
        @NotNull PhotoVisibility visibility
) {}
