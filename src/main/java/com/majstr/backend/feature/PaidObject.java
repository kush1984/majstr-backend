package com.majstr.backend.feature;

import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;

/**
 * What a one-off paid object (V214, {@code projects.paid_at}) adds on top of the owner's plan. Its
 * per-object limits live in {@link LimitService}; the one feature it carries is the logo on that
 * object's documents. Nothing else: the AI features stay PRO.
 */
public final class PaidObject {

    private PaidObject() {}

    /** The logo on this object's PDFs: the plan's {@link Feature#BRANDED_PDF}, or a paid object. */
    public static boolean branded(FeatureGuard guard, User owner, Project project) {
        return guard.isEnabled(owner, Feature.BRANDED_PDF) || (project != null && project.getPaidAt() != null);
    }
}
