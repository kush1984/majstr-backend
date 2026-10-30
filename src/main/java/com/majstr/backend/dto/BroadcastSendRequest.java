package com.majstr.backend.dto;

import com.majstr.backend.entity.BroadcastSegment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The real send. {@code userIds} are the rows the admin left ticked; the server recomputes the
 * segment and sends only to their INTERSECTION, so somebody who left it meanwhile gets nothing.
 */
public record BroadcastSendRequest(
        @NotNull BroadcastSegment segment,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 20000) String body,
        @NotEmpty List<UUID> userIds
) {}
