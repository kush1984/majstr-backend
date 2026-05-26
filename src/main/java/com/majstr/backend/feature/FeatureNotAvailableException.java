package com.majstr.backend.feature;

public class FeatureNotAvailableException extends RuntimeException {
    private final Feature feature;

    public FeatureNotAvailableException(Feature feature) {
        super("Feature not available on current plan: " + feature);
        this.feature = feature;
    }

    public Feature getFeature() {
        return feature;
    }
}
