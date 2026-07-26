package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers <b>401</b> when a request arrives with no usable authentication.
 *
 * <p>Without this bean Spring Security falls back to {@code Http403ForbiddenEntryPoint} —
 * the default whenever no login mechanism (form/basic) is configured — so a missing, expired
 * or malformed token produced a <b>403</b>. That contradicted this project's own documented
 * status mapping and, worse, collided with the 403s the API issues deliberately: plan limits
 * ({@code *_LIMIT_REACHED}), {@code EMAIL_NOT_VERIFIED}, and cross-owner access. One status
 * meant both "log in again" and "your plan forbids this", which a client cannot tell apart.
 *
 * <p>The PWA's axios interceptor refreshes the token on <b>401 only</b>, so under the old
 * behaviour a token the server rejected (clock skew, revoked, rotated away, secret changed)
 * never triggered a refresh — the request just failed. {@code ExceptionTranslationFilter}
 * routes anonymous requests here and authenticated-but-forbidden ones to
 * {@link RestAccessDeniedHandler}, which restores the intended split.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final MessageSource messages;
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Runs inside the filter chain, before the DispatcherServlet sets the locale context —
        // hence requestLocale(), the same rule the rate-limit filters follow.
        String message = messages.getMessage("error.auth-failed", null,
                LocalizationConfig.requestLocale(request));
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(), message, request.getRequestURI());
        write(response, HttpStatus.UNAUTHORIZED, body, objectMapper);
    }

    /** Shared by the access-denied handler — same JSON shape, same encoding. */
    static void write(HttpServletResponse response, HttpStatus status, ErrorResponse body,
                      ObjectMapper objectMapper) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
