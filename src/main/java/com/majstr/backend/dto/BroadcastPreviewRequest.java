package com.majstr.backend.dto;

import com.majstr.backend.entity.BroadcastSegment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A draft letter — rendered for the preview or sent to the admin's own address as a test. */
public record BroadcastPreviewRequest(
        @NotNull BroadcastSegment segment,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 20000) String body
) {}
