package com.majstr.backend.entity;

/** Where a master's rating of Majstr stands (V218). The column's CHECK lists the same values. */
public enum AppReviewStatus {
    /** Not looked at yet. */
    NEW,
    /** On majstr.pro — only ever with his consent (a CHECK holds it). */
    PUBLISHED,
    /** Read, kept for us, not shown. */
    HIDDEN
}
