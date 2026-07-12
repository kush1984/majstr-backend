package com.majstr.backend.dto;

import com.majstr.backend.entity.MeasurementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Create / update a measurement element. {@code payload} is the raw entered data
 * (shape depends on {@code type}); the server computes {@code result} from it and stores
 * the payload for re-editing. Unit is derived from {@code type} server-side.
 */
public record MeasurementItemRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull MeasurementType type,
        @NotNull JsonNode payload,
        Integer sortOrder
) {}
