package com.majstr.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The master-confirmed (edited) sketch: rooms + measured elements to create. The server
 * recomputes every {@code result} from the payload — the client's numbers are never trusted.
 * Element payloads reuse {@link MeasurementItemRequest}, so the manual and sketch commit paths
 * share one validation + calc surface.
 */
public record SketchCommitRequest(
        @NotEmpty @Valid List<Room> rooms
) {
    public record Room(
            @NotBlank @Size(max = 255) String name,
            @NotEmpty @Valid List<MeasurementItemRequest> items
    ) {}
}
