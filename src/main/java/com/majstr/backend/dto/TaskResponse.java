package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectTask;

import java.time.Instant;
import java.util.UUID;

/** An object task as returned to its owner. Owner-only — never in a portal/PDF/share response. */
public record TaskResponse(
        UUID id,
        String body,
        boolean done,
        Instant doneAt,
        Instant createdAt
) {
    public static TaskResponse from(ProjectTask task) {
        return new TaskResponse(task.getId(), task.getBody(), task.getDoneAt() != null,
                task.getDoneAt(), task.getCreatedAt());
    }
}
