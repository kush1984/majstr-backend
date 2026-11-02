package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.service.AbuseLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A coarse per-IP ceiling on the anonymous endpoints no other limiter covers (review B-128). High enough
 * that a carrier's NAT never meets it: it only stops a flood.
 */
@Component
@RequiredArgsConstructor
public class AnonymousRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> PATHS = Set.of("/api/auth/refresh", "/api/auth/reset",
            "/api/auth/verify-email", "/api/auth/logout", "/api/billing/webhook");
    private static final String FILES_PREFIX = "/api/files/";

    private final AbuseLimits abuseLimits;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !PATHS.contains(path) && !path.startsWith(FILES_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            abuseLimits.requireAnonymous(ClientIp.of(request));
        } catch (TooManyRequestsException e) {
            String message = messages.getMessage(e.getMessage(), null, LocalizationConfig.requestLocale(request));
            ErrorResponse body = ErrorResponse.rateLimited(message, request.getRequestURI(), e.getRetryAfterSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()));
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        chain.doFilter(request, response);
    }
}
