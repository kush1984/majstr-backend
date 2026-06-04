package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Identifies the browser subscription to remove (by its push endpoint). */
public record PushUnsubscribeRequest(
        @NotBlank @Size(max = 2048) String endpoint
) {}
