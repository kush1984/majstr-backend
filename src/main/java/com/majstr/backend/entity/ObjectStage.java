package com.majstr.backend.entity;

import java.time.Instant;

/**
 * The single, unified object-status vocabulary shown on the card, the list filters, and the
 * dashboard metrics (object-status-unification iteration). Before this, the card badge showed the
 * LATEST ESTIMATE's status (DRAFT/SENT/SIGNED/REJECTED) when one existed and the object's own
 * {@link ProjectStatus} otherwise, the list filter chip counted objects by that same estimate
 * status, and the dashboard's "Очікує" metric counted SENT ESTIMATES rather than objects — three
 * different numbers that could legitimately disagree ("Очікує 1" on the dashboard vs "Очікує · 0"
 * in the filter). This enum is the one derived answer everything now reads.
 *
 * <p><b>Storage:</b> only two states are manual/stored — {@code CANCELLED} (reuses the existing,
 * previously-unused {@link ProjectStatus#CANCELLED} value) and {@code COMPLETED} (reuses the
 * existing {@code completedAt} timestamp). No migration. The three active sub-stages are never
 * persisted — {@link #derive} recomputes them from whether the object has a SIGNED or SENT
 * estimate. Priority is top-down: a cancelled object reads CANCELLED even if it also has a signed
 * estimate; a completed one reads COMPLETED even if — unusually — it also has a SENT estimate.</p>
 */
public enum ObjectStage {
    ASSESSMENT,
    PENDING_SIGNATURE,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /**
     * @param status      the object's own (now largely vestigial) status — only {@code CANCELLED}
     *                    is read from it; every other value is treated as "not cancelled."
     * @param completedAt non-null means the master explicitly completed the object.
     * @param hasSigned   the object has at least one SIGNED estimate.
     * @param hasSent     the object has at least one SENT estimate (not yet signed).
     */
    public static ObjectStage derive(ProjectStatus status, Instant completedAt,
                                      boolean hasSigned, boolean hasSent) {
        if (status == ProjectStatus.CANCELLED) {
            return CANCELLED;
        }
        if (completedAt != null) {
            return COMPLETED;
        }
        if (hasSigned) {
            return IN_PROGRESS;
        }
        if (hasSent) {
            return PENDING_SIGNATURE;
        }
        return ASSESSMENT;
    }
}
