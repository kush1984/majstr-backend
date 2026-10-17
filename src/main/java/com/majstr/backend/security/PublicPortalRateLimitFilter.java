package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import com.majstr.backend.service.PortalRateLimiter;
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

@Component
@RequiredArgsConstructor
public class PublicPortalRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/api/public/";
    /** The public calculators count on their own bucket — see {@code PublicCalcRateLimiter}. */
    private static final String CALC_PREFIX = "/api/public/materials/";

    private final PortalRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(PATH_PREFIX) || uri.startsWith(CALC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = ClientIp.of(request);
        PortalRateLimiter.ConsumeResult result = rateLimiter.tryConsume(ip, linkToken(request.getRequestURI()),
                HttpMethod.POST.matches(request.getMethod()));
        if (!result.allowed()) {
            writeRateLimited(request, response, result.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The link a request is about: {@code /api/public/<kind>/<token>/…} — portal, estimates, economy,
     * act, message-link alike. Null for a route that names no link ({@code /api/public/monitoring}).
     */
    static String linkToken(String uri) {
        String[] parts = uri.substring(PATH_PREFIX.length()).split("/");
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : null;
    }

    private void writeRateLimited(HttpServletRequest request, HttpServletResponse response,
                                  long retryAfterSeconds) throws IOException {
        String message = messages.getMessage("error.rate.portal", null,
                LocalizationConfig.requestLocale(request));
        ErrorResponse body = ErrorResponse.rateLimited(message, request.getRequestURI(), retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
