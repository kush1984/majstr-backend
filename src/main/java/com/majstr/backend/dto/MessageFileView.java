package com.majstr.backend.dto;

import com.majstr.backend.entity.ProjectMessageFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * An attachment as the master sees it in the list.
 *
 * <p>No storage key and no URL: the client asks for a file by id through the owner-authenticated
 * endpoint. The name is the sender's, so the PWA renders it as text — it is a stranger's string.</p>
 */
public record MessageFileView(
        UUID id,
        String name,
        /** Sniffed from the bytes on upload, never the uploader's claim. Lets the PWA show a preview
         *  for a photo and a plain row for a PDF. */
        String contentType,
        long sizeBytes,
        boolean isImage,
        /**
         * When retention will delete this file, or null when it is not due.
         *
         * <p>A date rather than the raw warning timestamp, because that is the only thing worth showing:
         * the master needs to know how long they have, not when a cron job noticed. Opening the file
         * clears it, so a marker that has gone means the file is safe again.</p>
         */
        Instant deleteAfter
) {
    public static MessageFileView from(ProjectMessageFile f, int graceDays) {
        return new MessageFileView(
                f.getId(),
                f.getOriginalName(),
                f.getContentType(),
                f.getSizeBytes(),
                f.getContentType() != null && f.getContentType().startsWith("image/"),
                f.getDeletionWarnedAt() == null
                        ? null
                        : f.getDeletionWarnedAt().plus(graceDays, ChronoUnit.DAYS));
    }
}
