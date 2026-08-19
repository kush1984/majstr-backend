package com.majstr.backend.controller;

import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.service.PublicActPortalService;
import com.majstr.backend.service.QuestionRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The question endpoint of the public ACT portal writes (a stored message + a push to the master's
 * phone), so it carries its own rate limit — consumed BEFORE the write, keyed on IP AND token
 * together (same contract as the message link, and tested the same way).
 */
@ExtendWith(MockitoExtension.class)
class PublicActPortalControllerTest {

    @Mock PublicActPortalService service;
    @Mock QuestionRateLimiter rateLimiter;
    @InjectMocks PublicActPortalController controller;

    private MockMvc mockMvc;

    private static final String TOKEN = "act-tok";
    private static final String URL = "/api/public/act/" + TOKEN + "/question";
    private static final String BODY = """
            {"authorName": "Олена", "authorPhone": null, "message": "Питання щодо акта"}
            """;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        MessageSource source = messages;
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(source))
                .build();
    }

    @Test
    void theLimitIsAppliedBeforeTheWriteAndAnswers429WithRetryAfter() throws Exception {
        given(rateLimiter.tryConsume(anyString()))
                .willReturn(new QuestionRateLimiter.ConsumeResult(false, 420));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "420"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(420));

        verify(service, never()).question(anyString(), any(), anyString());
    }

    @Test
    void theLimitIsKeyedOnTheAddressAndTheTokenTogether() throws Exception {
        // Per-IP alone would let one leaked token be sprayed from many addresses; per-token alone
        // would let one address hit every portal a master has.
        given(rateLimiter.tryConsume(anyString()))
                .willReturn(new QuestionRateLimiter.ConsumeResult(true, 0));
        given(service.question(eq(TOKEN), any(), anyString()))
                .willReturn(new QuestionResponse(UUID.randomUUID(), Instant.now()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"))
                .andExpect(status().isCreated());

        verify(rateLimiter).tryConsume("203.0.113.9|" + TOKEN);
    }
}
