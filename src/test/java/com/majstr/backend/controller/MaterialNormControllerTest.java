package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialNormResponse;
import com.majstr.backend.dto.MaterialNormUpdateRequest;
import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.exception.MaterialNormValidationException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialNormService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MaterialNormControllerTest {

    @Mock private MaterialNormService normService;
    @InjectMocks private MaterialNormController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID normId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "master@example.com", "hash", Role.USER);

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

    /**
     * The answer carries the id the write LANDED on, which is the fork's, not the URL's. A caller
     * that keys its cache under the id it sent would show the master his edit disappearing.
     */
    @Test
    void theAnswerCarriesTheIdTheWriteLandedOn() throws Exception {
        UUID forkId = UUID.randomUUID();
        given(normService.saveOwn(eq(normId), eq(userId), any()))
                .willReturn(new MaterialNormResponse(forkId, UUID.randomUUID(),
                        new BigDecimal("1.2000"), true));

        mockMvc.perform(put("/api/material-norms/{id}", normId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MaterialNormUpdateRequest(new BigDecimal("1.2")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(forkId.toString()))
                .andExpect(jsonPath("$.ownNorm").value(true));
    }

    @Test
    void aCoefficientOfZeroIsRefusedBeforeItReachesTheService() throws Exception {
        mockMvc.perform(put("/api/material-norms/{id}", normId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MaterialNormUpdateRequest(BigDecimal.ZERO))))
                .andExpect(status().isBadRequest());

        verify(normService, never()).saveOwn(any(), any(), any());
    }

    @Test
    void aWorkThatConsumesNothingAnswers400WithItsOwnCode() throws Exception {
        willThrow(new MaterialNormValidationException("error.material-norm.no-material"))
                .given(normService).saveOwn(eq(normId), eq(userId), any());

        mockMvc.perform(put("/api/material-norms/{id}", normId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MaterialNormUpdateRequest(new BigDecimal("1.2")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATERIAL_NORM_INVALID"));
    }

    @Test
    void restoringTheDefaultIsScopedToThePrincipal() throws Exception {
        mockMvc.perform(delete("/api/material-norms/{id}", normId))
                .andExpect(status().isNoContent());

        verify(normService).restoreDefault(normId, userId);
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
