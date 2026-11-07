package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProjectRequest(
        @NotBlank @Size(max = 255) String name,
        // Optional since V192 — the name is enough to find the object; the address can be added on edit.
        @Size(max = 512) String address,
        @Size(max = 4000) String description,
        UUID clientId
) {}
