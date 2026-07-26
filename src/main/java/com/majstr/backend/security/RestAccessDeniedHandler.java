package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Answers <b>403</b> when the caller IS authenticated but lacks the authority — today that
 * means a non-admin token on {@code /api/admin/**}.
 *
 * <p>Paired with {@link RestAuthenticationEntryPoint}: "who are you?" is 401, "I know who you
 * are and the answer is no" is 403. Without this the denial would render as Spring's HTML
 * error page rather than the {@code ErrorResponse} shape every other endpoint returns.
 *
 * <p>An {@code AccessDeniedException} thrown from a <i>controller or service</i> (cross-owner
 * access, the {@code X-Entity-Uuid} idempotency check) never reaches here — {@code
 * GlobalExceptionHandler} handles it inside the DispatcherServlet, and still maps it to 403.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final MessageSource messages;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String message = messages.getMessage("error.access-denied", null,
                LocalizationConfig.requestLocale(request));
        ErrorResponse body = ErrorResponse.of(HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(), message, request.getRequestURI());
        RestAuthenticationEntryPoint.write(response, HttpStatus.FORBIDDEN, body, objectMapper);
    }
}
