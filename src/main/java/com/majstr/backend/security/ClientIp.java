package com.majstr.backend.security;

import jakarta.servlet.http.HttpServletRequest;

/** The caller's address behind the proxy: the first hop of {@code X-Forwarded-For}, else the socket. */
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
