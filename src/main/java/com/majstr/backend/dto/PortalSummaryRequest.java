package com.majstr.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Where the client's page shows «Зведення» (V202): after the positions, or at the top. */
public record PortalSummaryRequest(@NotNull Boolean atBottom) {}
