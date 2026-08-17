package com.majstr.backend.entity;

/**
 * Whether a work act closes the object or just a period of it (acts iteration).
 *
 * <ul>
 *   <li>{@link #INTERIM} — проміжний: one of possibly many; carries the «this is not final
 *       acceptance» disclaimer in the PDF.</li>
 *   <li>{@link #FINAL} — підсумковий: the last act for the object. Only one per object, and after it
 *       «+ Новий акт» is blocked. It is NOT an aggregate of previous periods — just a delta act that
 *       happens to be the last.</li>
 * </ul>
 */
public enum WorkActKind {
    INTERIM,
    FINAL
}
