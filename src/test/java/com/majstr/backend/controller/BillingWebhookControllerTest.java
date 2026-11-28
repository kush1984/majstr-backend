package com.majstr.backend.controller;

import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import com.majstr.backend.entity.Role;
import com.majstr.backend.security.UserPrincipal;

import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BillingWebhookControllerTest {

    @Mock BillingService billingService;
    @Mock UserTradeRepository userTradeRepository;
    @Mock com.majstr.backend.service.ProjectService projectService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        mvc = MockMvcBuilders.standaloneSetup(new BillingController(billingService, userTradeRepository, projectService))
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void aHandledWebhookAnswers200SoMonobankStops() throws Exception {
        mvc.perform(post("/api/billing/webhook").content("{}").header("X-Sign", "sig"))
                .andExpect(status().isOk());
    }

    @Test
    void aCheckoutToUnlockSomeoneElsesObjectIsRefusedBeforeAnyPayment() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "m@test.ua", "hash", Role.USER), null, List.of()));
        try {
            doThrow(new AccessDeniedException("not owner")).when(projectService).requireUnlockable(foreign, userId);

            mvc.perform(post("/api/billing/checkout-object").param("unlock", foreign.toString()))
                    .andExpect(status().isForbidden());

            verify(billingService, never()).checkoutObject(any(), any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aFailureWhileApplyingAnswers5xxSoMonobankDeliversAgain() throws Exception {
        doThrow(new IllegalStateException("db down")).when(billingService).handleWebhook(any(), any());

        mvc.perform(post("/api/billing/webhook").content("{}").header("X-Sign", "sig"))
                .andExpect(status().is5xxServerError());
    }
}
