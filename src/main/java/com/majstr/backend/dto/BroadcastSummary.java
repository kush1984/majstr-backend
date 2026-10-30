package com.majstr.backend.dto;

import com.majstr.backend.entity.BroadcastSegment;

import java.time.Instant;
import java.util.UUID;

/** A row of the send history. {@code finishedAt == null} while letters are still going out. */
public record BroadcastSummary(
        UUID id,
        BroadcastSegment segment,
        String segmentLabel,
        String subject,
        String sentBy,
        Instant createdAt,
        Instant finishedAt,
        long total,
        long sent,
        long failed,
        long skipped,
        long pending
) {}
