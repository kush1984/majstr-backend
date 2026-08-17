package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign an act on the client's behalf (the master downloaded the PDF, the client signed it on
 * paper). {@code signerName} is required; no IP / user-agent is recorded, and {@code signedOffline}
 * is set true.
 */
public record WorkActSignOfflineRequest(
        @NotBlank @Size(max = 255) String signerName
) {}
