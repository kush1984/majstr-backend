package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * «Як вас підписати в документі?» — asked the first time a document leaves for a client (V190,
 * {@code SendReadiness}). Writes these three fields and nothing else: the full profile PUT would
 * clear every requisite the sheet does not send. Phone and company are optional; blank clears them.
 */
public record SenderProfileRequest(
        @NotBlank @Size(max = 255) String fullName,
        @Size(max = 50) String phone,
        @Size(max = 255) String companyName
) {}
