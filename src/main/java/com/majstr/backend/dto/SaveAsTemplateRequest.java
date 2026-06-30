package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of "save the current estimate as a template" — the master's chosen name. */
public record SaveAsTemplateRequest(
        @NotBlank @Size(max = 255) String name
) {}
