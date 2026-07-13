package com.majstr.backend.feature;

/**
 * Per-plan numeric quotas. {@link PlanConfig} maps each limit to an int
 * value per plan, where {@code -1} means unlimited.
 *
 * <p>Project is the unit of value in Majstr — a contractor pays per active
 * project. Estimates are also capped <em>per project</em> on FREE: without it,
 * a contractor could keep two projects but spawn unlimited draft estimates,
 * sidestepping the paid plan.</p>
 */
public enum Limit {
    MAX_PROJECTS,
    MAX_ESTIMATES_PER_PROJECT,
    /** Progress photos («Фото» tab) an object may hold. FREE keeps a small taster;
     *  PRO/TEAM get a generous cap. Bounds storage without an unlimited free-for-all. */
    MAX_PHOTOS_PER_OBJECT,
    /** Receipt photos an object may hold — a separate budget so receipts don't eat the
     *  progress-photo cap. Reachable only on PRO/TEAM (receipt import is PRO-gated). */
    MAX_RECEIPT_PHOTOS_PER_OBJECT
}
