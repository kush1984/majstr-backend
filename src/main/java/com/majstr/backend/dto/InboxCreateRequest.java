package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A quick note typed or dictated in the PWA — parsed exactly like a bot message. */
public record InboxCreateRequest(
        @NotBlank @Size(max = 2000) String text
) {}
