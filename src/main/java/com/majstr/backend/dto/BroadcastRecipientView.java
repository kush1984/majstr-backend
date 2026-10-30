package com.majstr.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** A row of a segment's preview table. {@code lastSentAt} = when he last got THIS segment's letter. */
public record BroadcastRecipientView(
        UUID userId,
        String fullName,
        String email,
        Instant registeredAt,
        Instant lastActiveAt,
        long objects,
        long estimates,
        long maxEstimateLines,
        Instant lastSentAt
) {}
