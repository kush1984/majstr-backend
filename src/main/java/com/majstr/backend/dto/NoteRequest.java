package com.majstr.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create / update an object note. Only {@code body} is required — a master may just type
 * "keys are with the concierge". {@code title}/{@code phone} are optional; {@code phone}
 * is kept verbatim (not normalised) so both "067 123 45 67" and "+380…" work for tel:.
 */
public record NoteRequest(
        @Size(max = 255) String title,
        @Size(max = 40) String phone,
        @NotBlank @Size(max = 2000) String body
) {}
