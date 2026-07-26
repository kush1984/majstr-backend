package com.majstr.backend.service;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock CatalogTemplateService catalogTemplateService;
    @Mock EmailVerificationService emailVerificationService;
    @Mock ReferralService referralService;
    @Mock EmailPolicyService emailPolicyService;
    @InjectMocks AuthService authService;

    @Test
    void register_seedsCatalogAndIssuesVerificationEmail() {
        RegisterRequest req = new RegisterRequest("New@User.com", "Sup3rPass!", "Іван",
                Set.of(Trade.ELECTRICAL), "+380501112233", "FOP", true, null, null);
        given(emailPolicyService.canonicalize("new@user.com")).willReturn("new@user.com");
        given(userRepository.existsByEmailIgnoreCase("new@user.com")).willReturn(false);
        given(userRepository.existsByEmailCanonical("new@user.com")).willReturn(false);
        given(passwordEncoder.encode("Sup3rPass!")).willReturn("hash");
        given(referralService.resolve(null, null))
                .willReturn(new ReferralService.Attribution("DIRECT", null));
        given(referralService.generateUniqueCode()).willReturn("abc12345");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtService.generateAccessToken(any(), any())).willReturn("access");
        given(jwtService.accessTtlSeconds()).willReturn(900L);
        given(refreshTokenService.issue(any(User.class))).willReturn("refresh");

        AuthResponse resp = authService.register(req);

        assertThat(resp.accessToken()).isEqualTo("access");
        // New users start unverified and a verification email is issued.
        assertThat(resp.user().emailVerified()).isFalse();
        // Privacy consent is stamped at registration.
        assertThat(resp.user().consentedToPrivacyAt()).isNotNull();
        // A personal referral code is minted for the new master.
        assertThat(resp.user().referralCode()).isEqualTo("abc12345");
        verify(catalogTemplateService).seedForUser(any(User.class));
        verify(emailVerificationService).issueAndSend(any(User.class));
    }

    @Test
    void register_locksOnTheCanonicalEmailBEFOREcheckingItIsFree() {
        // The canonical column is anti-abuse: it stops one person farming a free plan per
        // gmail alias. Checking "is it taken?" and inserting are two statements, so firing
        // j.o.hn+1@, jo.hn+2@ … in parallel let every request read "free" and all of them
        // got an account. The advisory lock serialises them — but ONLY if taken first, so
        // the ordering is the assertion, not merely that the call happened.
        RegisterRequest req = new RegisterRequest("J.o.hn+2@gmail.com", "Sup3rPass!", "Іван",
                Set.of(Trade.ELECTRICAL), "+380501112233", "FOP", true, null, null);
        given(emailPolicyService.canonicalize("j.o.hn+2@gmail.com")).willReturn("john@gmail.com");
        given(userRepository.existsByEmailCanonical("john@gmail.com")).willReturn(true); // lost the race

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(EmailAlreadyExistsException.class);

        InOrder inOrder = inOrder(userRepository);
        inOrder.verify(userRepository).lockCanonicalEmail("john@gmail.com");
        inOrder.verify(userRepository).existsByEmailCanonical("john@gmail.com");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void logout_revokesTheRefreshToken() {
        authService.logout("some-refresh-token");

        verify(refreshTokenService).revoke("some-refresh-token");
    }
}
