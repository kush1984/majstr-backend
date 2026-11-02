package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Answers a repeated submission with what the first one answered (review P-78). These writes create things
 * but carry no id of their own, and a client timeout does not stop the server: the master saw a failure, tried
 * again, and got a second estimate or a duplicate photo eating the plan's cap.
 *
 * <p>One filter rather than a check in eight services, because each of them would be the same four steps.
 * Runs after Spring Security (no {@code @Order}, so last), which is what makes the principal available. A
 * request with no {@code X-Entity-Uuid} passes untouched, and only a success is remembered, so a failed
 * attempt really runs again.</p>
 */
@Component
@RequiredArgsConstructor
public class IdempotentWriteFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Entity-Uuid";
    private static final int MAX_STORED_BYTES = 1024 * 1024;
    private static final long IN_FLIGHT_RETRY_SECONDS = 5;

    private static final List<String> PATHS = List.of(
            "/api/projects/*/photos",
            "/api/estimates/*/dictation/commit",
            "/api/estimates/*/receipt-items/commit",
            "/api/estimates/import/commit",
            "/api/projects/*/measurements/project/commit",
            "/api/projects/*/measurements/sketch/commit",
            "/api/catalog/import/commit",
            "/api/projects/*/payments/split/commit");

    private final AntPathMatcher matcher = new AntPathMatcher();

    private final IdempotentRequestStore store;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || request.getHeader(HEADER) == null
                || PATHS.stream().noneMatch(p -> matcher.match(p, request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID userId = currentUser();
        UUID key = parseKey(request.getHeader(HEADER));
        if (userId == null || key == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        switch (store.claim(userId, key, path)) {
            case IdempotentRequestStore.Claim.New ignored -> runAndRemember(request, response, chain, userId, key, path);
            case IdempotentRequestStore.Claim.Done done -> replay(response, done);
            case IdempotentRequestStore.Claim.InFlight ignored -> refuseWhileRunning(request, response);
        }
    }

    private void runAndRemember(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                UUID userId, UUID key, String path) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean remembered = false;
        try {
            chain.doFilter(request, wrapper);
            byte[] body = wrapper.getContentAsByteArray();
            int status = wrapper.getStatus();
            if (status >= 200 && status < 300 && body.length <= MAX_STORED_BYTES) {
                store.complete(userId, key, path, status, wrapper.getContentType(), body);
                remembered = true;
            }
        } finally {
            if (!remembered) {
                store.release(userId, key, path);
            }
        }
        wrapper.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, IdempotentRequestStore.Claim.Done done) throws IOException {
        response.setStatus(done.status());
        if (done.contentType() != null) {
            response.setContentType(done.contentType());
        }
        if (done.body() != null) {
            response.getOutputStream().write(done.body());
        }
    }

    private void refuseWhileRunning(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String message = messages.getMessage("error.request.in-flight", null, LocalizationConfig.requestLocale(request));
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                message, request.getRequestURI(), "REQUEST_IN_FLIGHT");
        response.setStatus(HttpStatus.CONFLICT.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(IN_FLIGHT_RETRY_SECONDS));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static UUID currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof UserPrincipal principal ? principal.id() : null;
    }

    private static UUID parseKey(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }
}
