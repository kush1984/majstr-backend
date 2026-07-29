package com.majstr.backend.controller;

import com.majstr.backend.dto.MessageLinkInfo;
import com.majstr.backend.dto.MessageLinkRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.service.MessageLinkRateLimiter;
import com.majstr.backend.service.MessageLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public half of the message link: no auth at all, so what it accepts is the whole boundary.
 *
 * <p>Two things here are cheap to get wrong. The rate limit must be consumed BEFORE the write, or the
 * limit only reports abuse it already stored. And the IP recorded on a message must be the sender's
 * first {@code X-Forwarded-For} hop — behind the proxy every message would otherwise carry the proxy's
 * address, making the field useless for the one job it has.</p>
 */
@ExtendWith(MockitoExtension.class)
class MessageLinkControllerTest {

    @Mock MessageLinkService messageLinkService;
    @Mock MessageLinkRateLimiter rateLimiter;
    @InjectMocks MessageLinkController controller;

    private MockMvc mockMvc;

    private static final String TOKEN = "tok-abc";
    private static final String URL = "/api/public/message-link/" + TOKEN;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(testMessageSource()))
                .build();
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private void allowed() {
        given(rateLimiter.tryConsume(anyString()))
                .willReturn(new MessageLinkRateLimiter.ConsumeResult(true, 0));
    }

    /** The `data` part: the same JSON the static page sends. */
    private static MockMultipartFile dataPart(String name, String phone, String message) {
        String json = """
                {"authorName": %s, "authorPhone": %s, "message": %s}
                """.formatted(json(name), json(phone), json(message));
        return new MockMultipartFile("data", "data.json", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartHttpServletRequestBuilder submitRequest(
            String name, String phone, String message, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder req = multipart(URL).file(dataPart(name, phone, message));
        for (MockMultipartFile f : files) {
            req.file(f);
        }
        return req;
    }

    private static String json(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // =============================================================================================

    @Test
    void infoTellsWhoTheFormWritesTo() throws Exception {
        given(messageLinkService.info(TOKEN)).willReturn(new MessageLinkInfo("Квартира", "ФОП Іван"));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Квартира"))
                .andExpect(jsonPath("$.contractorName").value("ФОП Іван"));
    }

    @Test
    void aRevokedOrPortalTokenIs404() throws Exception {
        // The service resolves MESSAGE links only, so this is also the answer a portal token gets.
        given(messageLinkService.info(TOKEN)).willThrow(new ResourceNotFoundException("nope"));

        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
    }

    @Test
    void submitStoresTheMessage() throws Exception {
        allowed();
        given(messageLinkService.submit(eq(TOKEN), any(), any(), anyString())).willReturn(aResponse());

        mockMvc.perform(submitRequest("Постачальник", "+380671112233", "Рахунок"))
                .andExpect(status().isOk())
                // Only an id and a timestamp come back: the public form echoes nothing it was given.
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.authorName").doesNotExist());
    }

    @Test
    void aMessageWithNoPhoneIsAccepted() throws Exception {
        // The phone is optional by design — a supplier writing from a laptop has no reason to give one.
        allowed();
        given(messageLinkService.submit(eq(TOKEN), any(), any(), anyString())).willReturn(aResponse());

        mockMvc.perform(submitRequest("Постачальник", null, "Рахунок"))
                .andExpect(status().isOk());
    }

    @Test
    void aBlankNameIs400AndIsNeverStored() throws Exception {
        // Unlike the portal's question, the name is required here: an anonymous message pinned to an
        // object tells the master nothing about who to answer.
        mockMvc.perform(submitRequest("   ", null, "Рахунок"))
                .andExpect(status().isBadRequest());

        verify(messageLinkService, never()).submit(anyString(), any(), any(), anyString());
    }

    @Test
    void anEmptyMessageIs400() throws Exception {
        mockMvc.perform(submitRequest("Постачальник", null, "  "))
                .andExpect(status().isBadRequest());

        verify(messageLinkService, never()).submit(anyString(), any(), any(), anyString());
    }

    @Test
    void theLimitIsAppliedBeforeTheWriteAndAnswers429WithRetryAfter() throws Exception {
        given(rateLimiter.tryConsume(anyString()))
                .willReturn(new MessageLinkRateLimiter.ConsumeResult(false, 420));

        mockMvc.perform(submitRequest("Постачальник", null, "Рахунок"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "420"))
                // The static page reads this to say "try again in N minutes".
                .andExpect(jsonPath("$.retryAfterSeconds").value(420));

        verify(messageLinkService, never()).submit(anyString(), any(), any(), anyString());
    }

    @Test
    void theLimitIsKeyedOnTheAddressAndTheLinkTogether() throws Exception {
        // Per-IP alone would let one leaked link be filled from many addresses; per-link alone would let
        // one address spray every link a master has.
        allowed();
        given(messageLinkService.submit(eq(TOKEN), any(), any(), anyString())).willReturn(aResponse());

        mockMvc.perform(submitRequest("Постачальник", null, "Рахунок")
                        .header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(key.capture());
        assertThat(key.getValue()).isEqualTo("203.0.113.7|" + TOKEN);
    }

    @Test
    void theSenderIpIsTheFirstForwardedHopNotTheProxy() throws Exception {
        allowed();
        given(messageLinkService.submit(eq(TOKEN), any(), any(), anyString())).willReturn(aResponse());

        mockMvc.perform(submitRequest("Постачальник", null, "Рахунок")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.0.0.2"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        verify(messageLinkService).submit(eq(TOKEN), any(MessageLinkRequest.class), any(), ip.capture());
        assertThat(ip.getValue()).as("перший хоп — справжній відправник").isEqualTo("203.0.113.7");
    }

    private static QuestionResponse aResponse() {
        return new QuestionResponse(UUID.randomUUID(), Instant.now());
    }
}
