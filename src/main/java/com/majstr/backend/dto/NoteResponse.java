package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectNote;

import java.time.Instant;
import java.util.UUID;

/** An object note as returned to its owner. Owner-only — never in a portal/PDF/share response. */
public record NoteResponse(
        UUID id,
        String title,
        String phone,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    public static NoteResponse from(ProjectNote note) {
        return new NoteResponse(
                note.getId(),
                note.getTitle(),
                note.getPhone(),
                note.getBody(),
                note.getCreatedAt(),
                note.getUpdatedAt());
    }
}
