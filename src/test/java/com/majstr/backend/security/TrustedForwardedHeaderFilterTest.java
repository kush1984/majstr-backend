package com.majstr.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedForwardedHeaderFilterTest {

    private final TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter();

    private String remoteAddrSeenWith(String... headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1");
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest().getRemoteAddr();
    }

    @Test
    void aClientSentForwardedHeaderChangesNothing() throws Exception {
        assertThat(remoteAddrSeenWith("Forwarded", "for=7.7.7.7")).isEqualTo("10.0.0.1");
        assertThat(remoteAddrSeenWith("forwarded", "for=7.7.7.7")).isEqualTo("10.0.0.1");
    }

    @Test
    void theEdgesXForwardedForStillNamesTheClient() throws Exception {
        assertThat(remoteAddrSeenWith("X-Forwarded-For", "203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void forwardedCannotOutrankTheEdge() throws Exception {
        assertThat(remoteAddrSeenWith("Forwarded", "for=7.7.7.7", "X-Forwarded-For", "203.0.113.9"))
                .isEqualTo("203.0.113.9");
    }
}
