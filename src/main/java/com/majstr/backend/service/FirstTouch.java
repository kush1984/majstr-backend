package com.majstr.backend.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The first-touch referrer host and entry page (V178), as the registration stores them.
 *
 * <p>Both arrive from the visitor's own browser, so the server keeps only a plain shape and drops
 * the rest: a host is lower-cased, loses its {@code www.}, and must be letters, digits, dots and
 * hyphens; a path must start with {@code /} and loses any query or fragment — a query is where a
 * token or a personal value would ride, and the report only needs WHICH page.</p>
 */
final class FirstTouch {

    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,100}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9/_.-]{0,99}");

    private FirstTouch() {
    }

    static String host(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return HOST.matcher(host).matches() && host.contains(".") ? host : null;
    }

    static String path(String raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.trim();
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        return PATH.matcher(path).matches() ? path : null;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
