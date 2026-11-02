package com.majstr.backend.security;

import java.util.regex.Pattern;

/**
 * A request path fit for a log line or a Sentry tag (review B-130). A public path carries the capability
 * itself: whoever reads {@code /api/public/portal/<token>/…} can open that portal.
 */
public final class SafePath {

    private static final Pattern CAPABILITY =
            Pattern.compile("(/api/public/(?!materials\\b|monitoring\\b)[^/\\s?\"']+/)[^/\\s?\"']+");

    private SafePath() {
    }

    /** Works on a bare path and on text that merely contains one, such as an exception message. */
    public static String of(String text) {
        return text == null ? null : CAPABILITY.matcher(text).replaceAll("$1***");
    }
}
