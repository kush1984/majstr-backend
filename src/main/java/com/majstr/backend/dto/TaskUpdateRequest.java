package com.majstr.backend.dto;

import jakarta.validation.constraints.Size;

/** A PATCH: an absent field keeps its value. {@code done} ticks or unticks the task. */
public record TaskUpdateRequest(
        @Size(max = 500) String body,
        Boolean done
) {}
