package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create / rename a measurement room. {@code floor} is a free-text label («1», «цоколь»);
 *  blank clears it. */
public record MeasurementRoomRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 20) String floor,
        Integer sortOrder
) {}
