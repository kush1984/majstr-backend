package com.majstr.backend.controller;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AuthService;
import com.majstr.backend.service.EmailVerificationService;
import com.majstr.backend.service.VerificationEmailRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure-Mockito controller test. Spring Boot 4 removed slice annotations like
 * @WebMvcTest / @AutoConfigureMockMvc / @MockitoBean usage with slices, so we
 * wire MockMvc manually with standaloneSetup. Faster and no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private VerificationEmailRateLimiter verificationEmailRateLimiter;

    @InjectMocks
    private AuthController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
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

    @Test
    void register_returns201AndTokens() throws Exception {
        RegisterRequest req = new RegisterRequest(
                "john@example.com",
                "Sup3r-Secret!",
                "John Smith",
                Set.of(Trade.ELECTRICAL),
                "+15551234567",
                "Smith Electrical LLC",
                true);

        given(authService.register(any(RegisterRequest.class)))
                .willReturn(sampleAuthResponse("john@example.com"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", is("access-jwt")))
                .andExpect(jsonPath("$.refreshToken", is("refresh-token")))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is("john@example.com")))
                .andExpect(jsonPath("$.user.trades", hasItem("ELECTRICAL")));
    }

    @Test
    void register_rejectsInvalidEmail() throws Exception {
        RegisterRequest invalid = new RegisterRequest(
                "not-an-email",
                "Sup3r-Secret!",
                "John Smith",
                Set.of(Trade.GENERAL),
                "+15551234567",
                "Company",
                true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void register_rejectsWithoutConsent() throws Exception {
        // Privacy consent is mandatory (@AssertTrue) — a false flag is a 400.
        RegisterRequest noConsent = new RegisterRequest(
                "john@example.com",
                "Sup3r-Secret!",
                "John Smith",
                Set.of(Trade.GENERAL),
                "+15551234567",
                "Company",
                false);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noConsent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void login_returns200AndTokens() throws Exception {
        LoginRequest req = new LoginRequest("john@example.com", "Sup3r-Secret!");
        given(authService.login(any(LoginRequest.class)))
                .willReturn(sampleAuthResponse("john@example.com"));

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

    @Test
    void login_rejectsOversizedPassword() throws Exception {
        // BCrypt cost scales with input length — the cap stops cheap CPU abuse.
        LoginRequest invalid = new LoginRequest("john@example.com", "x".repeat(101));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    private AuthResponse sampleAuthResponse(String email) {
        UserResponse user = new UserResponse(
                UUID.randomUUID(),
                email,
                "John Smith",
                Set.of(Trade.ELECTRICAL),
                "+15551234567",
                "Smith Electrical LLC",
                null,
                com.majstr.backend.entity.Plan.FREE,
                com.majstr.backend.entity.Role.USER,
                true,
                Instant.now(),
                Instant.now(),
                null);
        return AuthResponse.of("access-jwt", "refresh-token", 900L, user);
    }
}
