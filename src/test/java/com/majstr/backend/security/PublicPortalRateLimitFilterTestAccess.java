package com.majstr.backend.security;

/** Opens the filter's package-private path parser to a test in another package. */
public final class PublicPortalRateLimitFilterTestAccess {

    private PublicPortalRateLimitFilterTestAccess() {
    }

    public static String linkToken(String uri) {
        return PublicPortalRateLimitFilter.linkToken(uri);
    }
}
