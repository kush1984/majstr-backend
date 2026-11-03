package com.majstr.backend.controller;

import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.AdminUserDeleteRefusedException;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AdminUserDeletionService;
import com.majstr.backend.service.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUserDeleteControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private AdminUserService adminUserService;
    @Mock private AdminUserDeletionService deletionService;

    @InjectMocks private AdminUserController controller;

    private MockMvc mockMvc;
    private final UUID adminId = UUID.randomUUID();

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
        UserPrincipal principal = new UserPrincipal(adminId, "admin@majstr.test", "hash", Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delete_answersNoContent_andPassesTheAdminAlong() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/users/{id}", userId))
                .andExpect(status().isNoContent());

        verify(deletionService).delete(userId, adminId);
    }

    @Test
    void aRefusedDelete_isAConflictWithItsOwnCode() throws Exception {
        willThrow(new AdminUserDeleteRefusedException()).given(deletionService).delete(adminId, adminId);

        mockMvc.perform(delete("/api/admin/users/{id}", adminId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_USER_DELETE_REFUSED"));
    }
}
