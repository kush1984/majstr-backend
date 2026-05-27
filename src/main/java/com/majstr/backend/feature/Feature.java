package com.majstr.backend.feature;

/**
 * Catalog of gated features. Wired through {@link FeatureGuard}; the
 * default impl ({@link NoOpFeatureGuard}) lets everything through. When
 * paid plans land we swap the impl, no call-site changes needed.
 */
public enum Feature {
    BRANDED_PDF,
    CLIENT_PORTAL,
    ONLINE_SIGNATURE,
    PHOTO_REPORTS,
    AI_ASSISTANT
}
