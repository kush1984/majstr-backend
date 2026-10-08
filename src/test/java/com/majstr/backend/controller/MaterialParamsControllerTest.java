package com.majstr.backend.controller;

import com.majstr.backend.dto.StoredMaterialParams;
import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialCalculatorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/estimates/{id}/materials/params} over HTTP (review B-112): the service test called
 * the service directly, so the DTO bounds — the only thing between a figure and a 500 — were never
 * exercised.
 */
@ExtendWith(MockitoExtension.class)
class MaterialParamsControllerTest {

    @Mock private MaterialCalculatorService calculatorService;
    @InjectMocks private MaterialCalculatorController controller;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "master@example.com", "hash", Role.USER), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAnswerIsRemembered() throws Exception {
        given(calculatorService.saveParams(eq(estimateId), eq(userId), any()))
                .willReturn(StoredMaterialParams.EMPTY);

        send("{\"thicknesses\":{\"" + itemId + "\":25}}").andExpect(status().isOk());
    }

    /** 0.0004 rounded to 0 in `numeric(12,3)` and failed `CHECK (value > 0)` as a 500. */
    @Test
    void aFigureFinerThanTheColumnIsA400_notA500() throws Exception {
        send("{\"thicknesses\":{\"" + itemId + "\":0.0004}}").andExpect(status().isBadRequest());
        verify(calculatorService, never()).saveParams(any(), any(), any());
    }

    @Test
    void aSectionOverItsBoundIsA400() throws Exception {
        send("{\"sections\":{\"" + itemId + "\":6}}").andExpect(status().isBadRequest());
    }

    @Test
    void someoneElsesEstimateIsA404() throws Exception {
        given(calculatorService.saveParams(eq(estimateId), eq(userId), any()))
                .willThrow(new ResourceNotFoundException("Estimate not found"));

        send("{\"perimeter\":16}").andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mockMvc.perform(put("/api/estimates/{id}/materials/params", estimateId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
