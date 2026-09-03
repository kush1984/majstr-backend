package com.majstr.backend.controller;

import com.majstr.backend.dto.DictationCommitRequest;
import com.majstr.backend.dto.DictationParseRequest;
import com.majstr.backend.dto.DictationParseResponse;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.DictationRateLimiter;
import com.majstr.backend.service.ReceiptScanRateLimiter;
import com.majstr.backend.service.importer.DictationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
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

@ExtendWith(MockitoExtension.class)
class DictationControllerTest {

    @Mock private DictationService dictationService;
    @Mock private DictationRateLimiter dictationRateLimiter;
    @InjectMocks private DictationController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "john@example.com", "hash", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(testMessageSource()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void parse_returnsTheReviewProposal() throws Exception {
        given(dictationRateLimiter.tryConsume(userId))
                .willReturn(new ReceiptScanRateLimiter.ConsumeResult(true, 0L));
        given(dictationService.parse(eq(userId), eq(estimateId), anyString()))
                .willReturn(new DictationParseResponse(List.of(new DictationParseResponse.DictationItem(
                        "Поклейка шпалер", "поклеїти шпалери", Unit.M2, new BigDecimal("20"),
                        new BigDecimal("150.00"), ItemType.WORK, "Шпалери", UUID.randomUUID(), List.of()))));

        mockMvc.perform(post("/api/estimates/{id}/dictation/parse", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DictationParseRequest("поклеїти шпалери двадцять квадратів"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name", is("Поклейка шпалер")))
                .andExpect(jsonPath("$.items[0].spokenName", is("поклеїти шпалери")))
                .andExpect(jsonPath("$.items[0].issues").isEmpty());
    }

    @Test
    void parse_overTheHourlyCap_is429AndNeverReachesTheModel() throws Exception {
        given(dictationRateLimiter.tryConsume(userId))
                .willReturn(new ReceiptScanRateLimiter.ConsumeResult(false, 420L));

        mockMvc.perform(post("/api/estimates/{id}/dictation/parse", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictationParseRequest("поклеїти шпалери"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "420"))
                .andExpect(jsonPath("$.retryAfterSeconds", is(420)));

        verify(dictationService, never()).parse(any(), any(), anyString());
    }

    @Test
    void parse_blankText_is400AndCostsNothing() throws Exception {
        // Validation runs before the limiter, so an empty field must not burn an hourly slot either.
        mockMvc.perform(post("/api/estimates/{id}/dictation/parse", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictationParseRequest("   "))))
                .andExpect(status().isBadRequest());

        verify(dictationRateLimiter, never()).tryConsume(any());
        verify(dictationService, never()).parse(any(), any(), anyString());
    }

    @Test
    void commit_isNotRateLimited_appendingLinesSpendsNoModelCall() throws Exception {
        given(dictationService.commit(eq(userId), eq(estimateId), any(DictationCommitRequest.class)))
                .willReturn(null);

        mockMvc.perform(post("/api/estimates/{id}/dictation/commit", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictationCommitRequest(List.of(
                                new DictationCommitRequest.CommitItem("Поклейка шпалер", Unit.M2,
                                        new BigDecimal("20"), new BigDecimal("150"), ItemType.WORK, "Шпалери"))))))
                .andExpect(status().isOk());

        verify(dictationRateLimiter, never()).tryConsume(any());
    }

    @Test
    void commit_withNoItems_is400() throws Exception {
        mockMvc.perform(post("/api/estimates/{id}/dictation/commit", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictationCommitRequest(List.of()))))
                .andExpect(status().isBadRequest());

        verify(dictationService, never()).commit(any(), any(), any());
    }
}
