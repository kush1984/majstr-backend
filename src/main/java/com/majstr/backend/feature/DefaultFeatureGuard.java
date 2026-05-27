package com.majstr.backend.feature;

import com.majstr.backend.entity.User;
import org.springframework.stereotype.Component;

/**
 * Real {@link FeatureGuard} backed by {@link PlanConfig}. Replaces the
 * NoOp stub from step 3 — the interface and call sites stayed identical,
 * only this bean swapped in.
 */
@Component
public class DefaultFeatureGuard implements FeatureGuard {

    @Override
    public void requireFeature(User user, Feature feature) {
        if (!isEnabled(user, feature)) {
            throw new FeatureNotAvailableException(feature, user.getPlan());
        }
    }

    @Override
    public boolean isEnabled(User user, Feature feature) {
        return PlanConfig.has(user.getPlan(), feature);
    }
}
