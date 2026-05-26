package com.majstr.backend.feature;

import com.majstr.backend.entity.User;

/**
 * Gate to a paid feature. Two flavours of check:
 * <ul>
 *   <li>{@link #requireFeature} — hard gate. Throws
 *       {@link FeatureNotAvailableException} if the feature is off for the
 *       user, which {@code GlobalExceptionHandler} maps to 403.</li>
 *   <li>{@link #isEnabled} — soft gate. Returns boolean so the caller can
 *       degrade gracefully (e.g. render a PDF without the logo).</li>
 * </ul>
 * Call sites should never check tier flags directly — always go through
 * the guard so the eventual real impl is the single switch.
 */
public interface FeatureGuard {

    void requireFeature(User user, Feature feature);

    boolean isEnabled(User user, Feature feature);
}
