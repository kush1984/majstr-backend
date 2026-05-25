package com.majstr.backend.controller;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(AuthControllerTest.TestConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {}

    @Test
    void register_returns201AndTokens() throws Exception {
        RegisterRequest req = new RegisterRequest(
                "john@example.com",
                "Sup3r-Secret!",
                "John Smith",
                Trade.ELECTRICAL,
                "+15551234567",
                "Smith Electrical LLC");

        AuthResponse stubbed = sampleAuthResponse("john@example.com");
        given(authService.register(any(RegisterRequest.class))).willReturn(stubbed);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", is("access-jwt")))
                .andExpect(jsonPath("$.refreshToken", is("refresh-token")))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("john@example.com")))
                .andExpect(jsonPath("$.user.trade", is("ELECTRICAL")));
    }

    @Test
    void register_rejectsInvalidEmail() throws Exception {
        RegisterRequest invalid = new RegisterRequest(
                "not-an-email",
                "Sup3r-Secret!",
                "John Smith",
                Trade.GENERAL,
                "+15551234567",
                "Company");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void login_returns200AndTokens() throws Exception {
        LoginRequest req = new LoginRequest("john@example.com", "Sup3r-Secret!");
        AuthResponse stubbed = sampleAuthResponse("john@example.com");
        given(authService.login(any(LoginRequest.class))).willReturn(stubbed);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", is("access-jwt")))
                .andExpect(jsonPath("$.user.email", is("john@example.com")));
    }

    @Test
    void login_rejectsBlankPassword() throws Exception {
        LoginRequest invalid = new LoginRequest("john@example.com", "");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    private AuthResponse sampleAuthResponse(String email) {
        UserResponse user = new UserResponse(
                UUID.randomUUID(),
                email,
                "John Smith",
                Trade.ELECTRICAL,
                "+15551234567",
                "Smith Electrical LLC",
                null,
                Instant.now());
        return AuthResponse.of("access-jwt", "refresh-token", 900L, user);
    }
}
