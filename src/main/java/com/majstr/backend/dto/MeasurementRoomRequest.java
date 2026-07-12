package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create / rename a measurement room. */
public record MeasurementRoomRequest(
        @NotBlank @Size(max = 255) String name,
        Integer sortOrder
) {}
