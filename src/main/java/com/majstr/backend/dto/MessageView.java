package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectMessage;

import java.time.Instant;
import java.util.List;
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
        Instant createdAt,
        /** Photos and PDFs left with the message, oldest first. Empty, never null. */
        List<MessageFileView> files
) {
    /**
     * @param graceDays how long a warned attachment survives — needed to turn the warning timestamp
     *                  into the date the PWA shows. Server configuration, so it is passed in rather
     *                  than known here or by the client.
     */
    public static MessageView from(ProjectMessage q, int graceDays) {
        return new MessageView(
                q.getId(),
                q.getAuthorName(),
                q.getAuthorPhone(),
                q.getMessage(),
                // Null for a message that came through the link rather than off an estimate — and
                // also for one whose estimate was since deleted (the FK now clears it).
                q.getEstimate() == null ? null : q.getEstimate().getName(),
                q.isRead(),
                q.getCreatedAt(),
                // Read off the entity rather than passed in: a caller that forgot to supply the list
                // would quietly render a message as having no attachments.
                q.getFiles() == null ? List.of()
                        : q.getFiles().stream().map(f -> MessageFileView.from(f, graceDays)).toList());
    }
}
