package com.majstr.backend.security;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The ceiling on a request body. {@code spring.servlet.multipart.*} covers uploads and Tomcat's
 * {@code maxPostSize} covers forms; a JSON or raw body had no limit at all, so one anonymous request
 * could fill the heap ({@code readAllBytes} in the login filter ran before the rate limiter did).
 *
 * <p>Runs ahead of the security chain. A declared length past the limit is refused unread; a chunked
 * body declares none, so the stream itself counts and throws. Multipart keeps Spring's own limits,
 * except on the two prefixes that never take a file — otherwise a multipart Content-Type would be a
 * way around the ceiling for the login filter's read.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Credentials, a registration form, a gateway callback: small by nature. */
    static final long SMALL_BODY_BYTES = 64L * 1024;
    /** Past the largest legitimate JSON here: a full import commit of 500 lines, a whole reorder. */
    static final long DEFAULT_BODY_BYTES = 2L * 1024 * 1024;

    private static final String AUTH_PREFIX = "/api/auth/";
    private static final String BILLING_WEBHOOK = "/api/billing/webhook";

    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean small = uri.startsWith(AUTH_PREFIX) || uri.equals(BILLING_WEBHOOK);
        if (!small && isMultipart(request)) {
            chain.doFilter(request, response);
            return;
        }
        long limit = small ? SMALL_BODY_BYTES : DEFAULT_BODY_BYTES;
        if (request.getContentLengthLong() > limit) {
            refuse(request, response);
            return;
        }
        try {
            chain.doFilter(new BoundedBodyRequest(request, limit), response);
        } catch (RequestBodyTooLargeException e) {
            // Read by a filter (the login one) rather than by a message converter: nothing has been
            // written yet, and the advice never gets to see this.
            if (!response.isCommitted()) {
                refuse(request, response);
            }
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String message = messages.getMessage("error.request.too-large", null,
                LocalizationConfig.requestLocale(request));
        HttpStatus status = HttpStatus.PAYLOAD_TOO_LARGE;
        ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // The rest of the body stays unread, so this connection cannot carry another request.
        response.setHeader(HttpHeaders.CONNECTION, "close");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        BoundedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new BoundedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        BoundedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws RequestBodyTooLargeException {
            read += n;
            if (read > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
