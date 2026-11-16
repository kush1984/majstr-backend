package com.majstr.backend.dto;

import com.majstr.backend.entity.InboxEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A message in the inbox with what was found in it. */
public record InboxEntryView(
        UUID id,
        InboxEntry.Source source,
        InboxEntry.Status status,
        String rawText,
        Instant createdAt,
        List<InboxItemView> items,
        /** A voice note (V213): {@code rawText} is what was heard. */
        boolean voice
) {
    public InboxEntryView(UUID id, InboxEntry.Source source, InboxEntry.Status status, String rawText,
                          Instant createdAt, List<InboxItemView> items) {
        this(id, source, status, rawText, createdAt, items, false);
    }
}
