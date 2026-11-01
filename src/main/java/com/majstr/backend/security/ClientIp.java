package com.majstr.backend.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's address, for rate limits and signature evidence. The one place that reads it.
 *
 * <p>In production {@link TrustedForwardedHeaderFilter} has already applied the edge's address to
 * {@code getRemoteAddr()} and removed the forwarding headers, so the socket branch is what runs. The
 * header branch is for the {@code dev} profile, which runs with no such filter.</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
