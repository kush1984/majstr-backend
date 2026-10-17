package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.EstimateEmailRateLimiter;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ShareLinkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A bundle applied offline replays its lines through their own door (review P0-5): quantity 0 and
 * a price of 0 are what the online application creates, so they are accepted there — and ONLY there.
 * The manual door still refuses both.
 */
@ExtendWith(MockitoExtension.class)
class TemplateLineEndpointTest {

    @Mock private EstimateService estimateService;
    @Mock private ShareLinkService shareLinkService;
    @Mock private EstimateEmailRateLimiter estimateEmailRateLimiter;
    @InjectMocks private EstimateController controller;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "m@t.ua", "hash", Role.USER), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTemplateLineIsAcceptedWithQuantityZeroAndPriceZero_andStoredAsQuantityZero() throws Exception {
        UUID lineId = UUID.randomUUID();
        mockMvc.perform(post("/api/estimates/{id}/items/template-line", estimateId)
                        .header("X-Entity-Uuid", lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ґрунтування\",\"type\":\"WORK\",\"unit\":\"M2\",\"unitPrice\":0,\"sortOrder\":3}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<EstimateItemRequest> req = ArgumentCaptor.forClass(EstimateItemRequest.class);
        verify(estimateService).addItem(eq(estimateId), req.capture(), eq(userId), eq(lineId));
        assertThat(req.getValue().quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(req.getValue().unitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(req.getValue().sortOrder()).isEqualTo(3);
    }

    @Test
    void theManualDoorStillRefusesQuantityZero() throws Exception {
        mockMvc.perform(post("/api/estimates/{id}/items", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Робота\",\"type\":\"WORK\",\"unit\":\"M2\",\"quantity\":0,\"unitPrice\":100}"))
                .andExpect(status().isBadRequest());
        verify(estimateService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void aTemplateLineCannotBeAPercentageOrNegative() throws Exception {
        mockMvc.perform(post("/api/estimates/{id}/items/template-line", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Націнка\",\"type\":\"WORK\",\"unit\":\"PERCENT\",\"unitPrice\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/estimates/{id}/items/template-line", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Робота\",\"type\":\"WORK\",\"unit\":\"M2\",\"unitPrice\":-5}"))
                .andExpect(status().isBadRequest());
        verify(estimateService, never()).addItem(any(), any(), any(), any());
    }
}
