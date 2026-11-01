package com.majstr.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Spring's filter, minus the one header our edge does not own.
 *
 * <p>{@code ForwardedHeaderFilter} prefers the standard {@code Forwarded} header over
 * {@code X-Forwarded-For}. Railway's edge replaces {@code X-Forwarded-For} but passes a client's
 * {@code Forwarded} straight through, so one header let any caller choose its own
 * {@code getRemoteAddr()} — a fresh bucket in every IP limit, and any address it liked on a
 * signature. Measured against production: seven logins with a rotating {@code Forwarded} all answered
 * 401, while a rotating {@code X-Forwarded-For} hit 429 on the sixth. If that ever changes on
 * Railway's side, this is the class to revisit.</p>
 */
public class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

    private static final String UNTRUSTED = "Forwarded";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        super.doFilterInternal(new WithoutUntrustedHeader(request), response, chain);
    }

    private static final class WithoutUntrustedHeader extends HttpServletRequestWrapper {

        WithoutUntrustedHeader(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return UNTRUSTED.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return UNTRUSTED.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(UNTRUSTED::equalsIgnoreCase);
            return Collections.enumeration(names);
        }
    }
}
