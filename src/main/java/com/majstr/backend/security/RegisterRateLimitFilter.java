package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import com.majstr.backend.service.RegisterRateLimiter;
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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Rate-limits {@code POST /api/auth/register} per client IP. No body parsing
 * needed (there is no account to key on yet), so the request passes through
 * unwrapped — unlike the login filter.
 */
@Component
@RequiredArgsConstructor
public class RegisterRateLimitFilter extends OncePerRequestFilter {

    private static final String REGISTER_PATH = "/api/auth/register";

    private final RegisterRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && REGISTER_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = ClientIp.of(request);
        RegisterRateLimiter.ConsumeResult result = rateLimiter.tryConsume(ip);
        if (!result.allowed()) {
            writeRateLimited(request, response, result.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
        // A refused form created nothing, so it is not an attempt. A 409 (address taken) still
        // counts: refunding it would let anyone test which addresses have an account.
        if (response.getStatus() == HttpStatus.BAD_REQUEST.value()) {
            rateLimiter.refund(ip);
        }
    }

    private void writeRateLimited(HttpServletRequest request, HttpServletResponse response,
                                  long retryAfterSeconds) throws IOException {
        String message = messages.getMessage("error.rate.register", null,
                LocalizationConfig.requestLocale(request));
        ErrorResponse body = ErrorResponse.rateLimited(message, request.getRequestURI(), retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
