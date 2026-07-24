package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The master-confirmed (edited) project import: rooms with their floor label and
 * the element package to create. Element payloads reuse {@link MeasurementItemRequest},
 * so the manual / sketch / project commit paths share one validation + calc surface —
 * the server recomputes every {@code result}, the client's numbers are never trusted.
 */
public record ProjectImportCommitRequest(
        @NotEmpty @Valid List<Room> rooms
) {
    public record Room(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 20) String floor,
            @NotEmpty @Valid List<MeasurementItemRequest> items
    ) {}
}
