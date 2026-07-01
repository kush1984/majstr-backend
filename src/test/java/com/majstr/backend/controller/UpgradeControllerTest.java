package com.majstr.backend.controller;

import com.majstr.backend.entity.Role;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.UpgradeEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UpgradeControllerTest {

    @Mock private UpgradeEventService upgradeEventService;
    @InjectMocks private UpgradeController controller;

    private MockMvc mockMvc;
    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(userId, "john@example.com", "hash", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void click_recordsWithTriggerAndReturns204() throws Exception {
        mockMvc.perform(post("/api/upgrade/click")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trigger\":\"OBJECT_LIMIT\"}"))
                .andExpect(status().isNoContent());
        verify(upgradeEventService).recordClick(userId, "OBJECT_LIMIT");
    }

    @Test
    void interest_recordsReasonAndReturns204() throws Exception {
        mockMvc.perform(post("/api/upgrade/interest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"потрібен командний доступ\"}"))
                .andExpect(status().isNoContent());
        verify(upgradeEventService).recordInterest(userId, "потрібен командний доступ");
    }
}
