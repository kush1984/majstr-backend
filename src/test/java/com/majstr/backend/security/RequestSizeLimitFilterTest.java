package com.majstr.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(JsonMapper.builder().build(), messages());

    private static StaticMessageSource messages() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    private static MockHttpServletRequest post(String uri, byte[] body, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(body);
        request.setContentType(contentType);
        return request;
    }

    /** Declares no length, the way a chunked request arrives. */
    private static MockHttpServletRequest chunked(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        request.setContentType("application/json");
        return request;
    }

    @Test
    void aBodyWithinTheLimitPassesAndReadsWhole() throws Exception {
        byte[] body = new byte[1024];
        AtomicInteger read = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/api/auth/login", body, "application/json"), response,
                (req, res) -> read.set(req.getInputStream().readAllBytes().length));

        assertThat(read.get()).isEqualTo(1024);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aDeclaredLengthPastTheLimitIsRefusedUnread() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.SMALL_BODY_BYTES + 1];
        AtomicBoolean reached = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/api/auth/login", body, "application/json"), response, (req, res) -> reached.set(true));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("error.request.too-large");
    }

    @Test
    void aChunkedBodyIsCutOffAtTheLimitWhenAFilterReadsIt() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.SMALL_BODY_BYTES + 1];
        MockHttpServletResponse response = new MockHttpServletResponse();

        // What LoginRateLimitFilter does through CachedBodyHttpServletRequest.
        FilterChain readsEverything = (req, res) -> req.getInputStream().readAllBytes();
        filter.doFilter(chunked("/api/auth/login", body), response, readsEverything);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void aMultipartContentTypeIsNoWayAroundTheLoginCeiling() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.SMALL_BODY_BYTES + 1];
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/api/auth/login", body, "multipart/form-data; boundary=x"), response,
                (req, res) -> req.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void anOrdinaryUploadKeepsSpringsOwnLimits() throws Exception {
        byte[] body = new byte[(int) RequestSizeLimitFilter.DEFAULT_BODY_BYTES + 1];
        AtomicInteger read = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/api/projects/1/photos", body, "multipart/form-data; boundary=x"), response,
                (req, res) -> read.set(req.getInputStream().readAllBytes().length));

        assertThat(read.get()).isEqualTo(body.length);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void jsonEverywhereElseHasTheDefaultCeiling() throws Exception {
        byte[] fits = new byte[(int) RequestSizeLimitFilter.SMALL_BODY_BYTES + 1];
        byte[] tooBig = new byte[(int) RequestSizeLimitFilter.DEFAULT_BODY_BYTES + 1];
        MockHttpServletResponse ok = new MockHttpServletResponse();
        MockHttpServletResponse refused = new MockHttpServletResponse();

        filter.doFilter(post("/api/catalog/import/commit", fits, "application/json"), ok,
                (req, res) -> req.getInputStream().readAllBytes());
        filter.doFilter(post("/api/catalog/import/commit", tooBig, "application/json"), refused,
                (req, res) -> req.getInputStream().readAllBytes());

        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(refused.getStatus()).isEqualTo(413);
    }
}
