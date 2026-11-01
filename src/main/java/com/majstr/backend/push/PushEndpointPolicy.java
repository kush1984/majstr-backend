package com.majstr.backend.push;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the server may send a Web Push. The endpoint comes from the browser, which is to say from
 * whoever is calling the API, and the server POSTs to it: without this list any account could make
 * the backend connect to an internal address, or to a host that answers nothing, for as long as it
 * liked. HTTPS on the default port, to the browsers' own push services, and nothing else.
 */
final class PushEndpointPolicy {

    private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "android.googleapis.com");
    private static final List<String> DOMAINS = List.of(
            ".push.apple.com", ".notify.windows.com", ".push.services.mozilla.com");

    private PushEndpointPolicy() {
    }

    static boolean allows(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            String name = host.toLowerCase(Locale.ROOT);
            return HOSTS.contains(name) || DOMAINS.stream().anyMatch(name::endsWith);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
