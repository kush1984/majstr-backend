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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BillingWebhookControllerTest {

    @Mock BillingService billingService;
    @Mock UserTradeRepository userTradeRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        mvc = MockMvcBuilders.standaloneSetup(new BillingController(billingService, userTradeRepository))
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .build();
    }

    @Test
    void aHandledWebhookAnswers200SoMonobankStops() throws Exception {
        mvc.perform(post("/api/billing/webhook").content("{}").header("X-Sign", "sig"))
                .andExpect(status().isOk());
    }

    @Test
    void aFailureWhileApplyingAnswers5xxSoMonobankDeliversAgain() throws Exception {
        doThrow(new IllegalStateException("db down")).when(billingService).handleWebhook(any(), any());

        mvc.perform(post("/api/billing/webhook").content("{}").header("X-Sign", "sig"))
                .andExpect(status().is5xxServerError());
    }
}
