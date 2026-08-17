package com.majstr.backend.entity;

/**
 * How a work act's running number is formatted for a master (acts iteration).
 *
 * <ul>
 *   <li>{@link #PLAIN} — «7» (a bare sequence number within the year);</li>
 *   <li>{@link #WITH_YEAR} — «7/2026».</li>
 * </ul>
 *
 * The numbering itself is per-user, per-year (see the acts core); this only changes the display.
 */
public enum ActNumberFormat {
    PLAIN,
    WITH_YEAR
}
