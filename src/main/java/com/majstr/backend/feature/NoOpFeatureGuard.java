package com.majstr.backend.feature;

import com.majstr.backend.entity.User;
import org.springframework.stereotype.Component;

/**
 * Default implementation while there are no paid plans: every feature is
 * available for every user. Replace this bean when entitlement checks
 * land — no call-site changes required.
 */
@Component
public class NoOpFeatureGuard implements FeatureGuard {

    @Override
    public void requireFeature(User user, Feature feature) {
        // No-op: all features allowed for all users.
    }

    @Override
    public boolean isEnabled(User user, Feature feature) {
        return true;
    }
}
