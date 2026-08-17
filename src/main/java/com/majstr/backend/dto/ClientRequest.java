package com.majstr.backend.dto;

import com.majstr.backend.entity.ClientType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClientRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 50) String phone,
        @Size(max = 512) String address,
        // Optional — format checked only when present.
        @Email @Size(max = 255) String email,
        // Document requisites (acts iteration) — all OPTIONAL. clientType null → treated as PERSON
        // by the service (backward-compatible: an old client sends no type). The legal fields are
        // only meaningful for FOP/COMPANY, but the backend stores whatever it's given.
        ClientType clientType,
        @Size(max = 20) String taxId,
        @Size(max = 255) String legalName,
        @Size(max = 512) String legalAddress,
        @Size(max = 120) String signatoryTitle,
        @Size(max = 255) String signatoryName
) {}
