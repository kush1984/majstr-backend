package com.majstr.backend.security;

import com.majstr.backend.service.RegisterRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RegisterRateLimitFilterTest {

    private final RegisterRateLimiter limiter = mock(RegisterRateLimiter.class);
    private final RegisterRateLimitFilter filter =
            new RegisterRateLimitFilter(limiter, JsonMapper.builder().build(), mock(MessageSource.class));

    /** The person whose address was refused corrects it; that refusal is not one of his tries. */
    @Test
    void aRefusedRegistrationGivesTheTryBack() throws Exception {
        answer(400);
        verify(limiter).refund("203.0.113.7");
    }

    /** «This address has an account» still counts, or the endpoint would test addresses for free. */
    @Test
    void anAddressAlreadyTakenStillCounts() throws Exception {
        answer(409);
        verify(limiter, never()).refund(anyString());
    }

    @Test
    void anAccountCreatedCounts() throws Exception {
        answer(201);
        verify(limiter, never()).refund(anyString());
    }

    private void answer(int status) throws Exception {
        given(limiter.tryConsume("203.0.113.7")).willReturn(new RegisterRateLimiter.ConsumeResult(true, 0L));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        request.setRemoteAddr("203.0.113.7");
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(status);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }
}
