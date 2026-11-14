package com.majstr.backend.service;

import com.majstr.backend.entity.EstimateItem;

/** The master's own comment on an estimate line (V206): what is stored, and what the client may read. */
public final class EstimateLineNote {

    private EstimateLineNote() {
    }

    /** Blank is no comment at all. */
    public static String clean(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    /** The comment as the client's page, the PDF and the signed snapshot print it — or null. */
    public static String forClient(EstimateItem item) {
        return item.isNoteVisible() ? clean(item.getNote()) : null;
    }
}
