package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Add an object task — the text is all there is. */
public record TaskRequest(
        @NotBlank @Size(max = 500) String body
) {}
