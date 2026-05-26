package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionRequest(
        @Size(max = 255) String authorName,
        @Size(max = 50) String authorPhone,
        @NotBlank @Size(max = 2000) String message
) {}
