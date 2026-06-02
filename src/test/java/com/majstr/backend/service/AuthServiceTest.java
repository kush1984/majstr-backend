package com.majstr.backend.service;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock CatalogTemplateService catalogTemplateService;
    @Mock EmailVerificationService emailVerificationService;
    @InjectMocks AuthService authService;

    @Test
    void register_seedsCatalogAndIssuesVerificationEmail() {
        RegisterRequest req = new RegisterRequest("New@User.com", "Sup3rPass!", "Іван",
                Set.of(Trade.ELECTRICAL), "+380501112233", "FOP");
        given(userRepository.existsByEmailIgnoreCase("new@user.com")).willReturn(false);
        given(passwordEncoder.encode("Sup3rPass!")).willReturn("hash");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtService.generateAccessToken(any(), any())).willReturn("access");
        given(jwtService.accessTtlSeconds()).willReturn(900L);
        given(refreshTokenService.issue(any(User.class))).willReturn("refresh");

        AuthResponse resp = authService.register(req);

        assertThat(resp.accessToken()).isEqualTo("access");
        // New users start unverified and a verification email is issued.
        assertThat(resp.user().emailVerified()).isFalse();
        verify(catalogTemplateService).seedForUser(any(User.class));
        verify(emailVerificationService).issueAndSend(any(User.class));
    }
}
