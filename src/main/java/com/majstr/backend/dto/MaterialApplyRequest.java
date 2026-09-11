package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** What the master ticked on the result screen, on its way to the shopping list or the estimate. */
public record MaterialApplyRequest(
        @NotEmpty @Valid List<MaterialLineRequest> materials
) {}
