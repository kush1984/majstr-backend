package com.majstr.backend.feature;

/**
 * Per-plan numeric quotas. {@link PlanConfig} maps each limit to an int
 * value per plan, where {@code -1} means unlimited.
 *
 * <p>Project is the unit of value in Majstr — a contractor pays per
 * active project, so it's the only thing we cap.</p>
 */
public enum Limit {
    MAX_PROJECTS
}
