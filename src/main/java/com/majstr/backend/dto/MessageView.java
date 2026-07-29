package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * A message on an object as the contractor sees it (full text + read state) — whether it came from a
 * client on the portal or from anyone the master sent their message link to.
 *
 * Distinct from {@link QuestionResponse}, which is the minimal acknowledgement handed back to whoever
 * submitted it. The field name {@code isRead} and the whole JSON shape are unchanged on purpose: the
 * installed PWA reads this, and renaming a field would have forced the two to deploy together.
 */
public record MessageView(
        UUID id,
        String authorName,
        String authorPhone,
        String message,
        /** Which estimate the client asked about (null = unnamed estimate) —
         *  a multi-estimate portal makes this necessary context. */
        String estimateName,
        boolean isRead,
        Instant createdAt
) {
    public static MessageView from(ProjectMessage q) {
        return new MessageView(
                q.getId(),
                q.getAuthorName(),
                q.getAuthorPhone(),
                q.getMessage(),
                // Null for a message that came through the link rather than off an estimate — and
                // also for one whose estimate was since deleted (the FK now clears it).
                q.getEstimate() == null ? null : q.getEstimate().getName(),
                q.isRead(),
                q.getCreatedAt());
    }
}
