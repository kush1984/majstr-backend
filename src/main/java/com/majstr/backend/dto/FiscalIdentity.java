package com.majstr.backend.dto;

/**
 * The one place the printed fiscal identity ({@code fn} + {@code id}) is normalised and turned into
 * a match key, shared by {@link ProjectReceiptRequest}, {@link WorkActReceiptRequest} and both sides
 * of B-04 (the duplicate read path and the sign-time reconciler) because the rule has to be the same
 * everywhere or the cross-table duplicate check compares apples to pears.
 *
 * <p><b>Blank is not an identity.</b> Storing {@code ""} used to make every such receipt the twin of
 * every other one — {@code key("", "")} was one key, the {@code <> ''} filters did not exist, and
 * {@code ActReceiptReconciler} settles on that key, so two unrelated papers could be reconciled into
 * each other at sign time and one of them silently lost its object expense. An unidentified receipt
 * must read as unidentified, and the ONLY way to guarantee that at every reader is for the key to be
 * computed here.</p>
 */
public final class FiscalIdentity {

    private FiscalIdentity() {
    }

    /** Trimmed, or null when there is nothing printed to remember. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Both halves present, or neither. Half an identity cannot be matched against anything, so it
     * is a client bug worth a 400 rather than a row that quietly identifies nothing.
     */
    public static boolean complete(String fn, String id) {
        return (normalize(fn) == null) == (normalize(id) == null);
    }

    /**
     * The match key of one stored paper, or {@code null} when the paper identifies nothing — a
     * hand-written товарний чек, or a legacy row that was saved blank before this class existed.
     * Normalises on read as well as on write: the rows already in the database were not.
     */
    public static String key(String fn, String id) {
        String normalizedFn = normalize(fn);
        String normalizedId = normalize(id);
        return normalizedFn == null || normalizedId == null ? null : normalizedFn + "|" + normalizedId;
    }
}
