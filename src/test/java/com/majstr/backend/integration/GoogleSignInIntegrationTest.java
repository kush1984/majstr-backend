package com.majstr.backend.integration;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.GoogleSignInRequest;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AuthService;
import com.majstr.backend.service.GoogleIdTokens;
import com.majstr.backend.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** «Увійти через Google»: one door that registers, signs in, and links a same-email account. */
@SpringBootTest
class GoogleSignInIntegrationTest extends IntegrationTestBase {

    @MockitoBean GoogleIdTokens googleIdTokens;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenService refreshTokenService;

    private String sub;
    private String email;

    @BeforeEach
    void google() {
        sub = "google-" + UUID.randomUUID();
        email = UUID.randomUUID() + "@gmail.com";
        when(googleIdTokens.enabled()).thenReturn(true);
        when(googleIdTokens.verify(anyString())).thenAnswer(inv -> new GoogleIdTokens.Identity(sub, email, "Петро Гугл"));
    }

    @Test
    void aNewGoogleAccountRegistersVerified_andTheSecondTimeSignsIntoTheSameOne() {
        AuthResponse first = authService.signInWithGoogle(request());
        AuthResponse second = authService.signInWithGoogle(request());

        User user = userRepository.findByGoogleSub(sub).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getFullName()).isEqualTo("Петро Гугл");
        assertThat(user.getConsentedToPrivacyAt()).isNotNull();
        assertThat(user.getUtmSource()).isEqualTo("tiktok");
        assertThat(first.user().id()).isEqualTo(second.user().id()).isEqualTo(user.getId());
    }

    @Test
    void aVerifiedAccountWithTheSameEmailIsLinked_andKeepsItsPasswordAndSessions() {
        User existing = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").emailVerified(true).plan(Plan.FREE)
                .role(Role.USER).referralCode(UUID.randomUUID().toString().substring(0, 10)).build());

        AuthResponse response = authService.signInWithGoogle(request());

        assertThat(response.user().id()).isEqualTo(existing.getId());
        User linked = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(linked.getGoogleSub()).isEqualTo(sub);
        assertThat(linked.getPasswordHash()).isEqualTo("x");
        assertThat(linked.getTokensValidAfter()).isNull();
    }

    @Test
    void anUnverifiedAccountWithTheSameEmail_losesThePasswordAndSessionsSomeoneElseMayHold() {
        User existing = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").plan(Plan.FREE).role(Role.USER)
                .referralCode(UUID.randomUUID().toString().substring(0, 10)).build());
        String squatterSession = refreshTokenService.issue(existing);

        AuthResponse response = authService.signInWithGoogle(request());

        assertThat(response.user().id()).isEqualTo(existing.getId());
        User linked = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(linked.getGoogleSub()).isEqualTo(sub);
        assertThat(linked.isEmailVerified()).isTrue();
        assertThat(linked.getPasswordHash()).isNotEqualTo("x");
        assertThat(linked.getTokensValidAfter()).isNotNull();
        assertThatThrownBy(() -> authService.refresh(squatterSession)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anEmailBoundToAnotherGoogleAccountIsNotTakenOver() {
        userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").plan(Plan.FREE).role(Role.USER)
                .googleSub("someone-else").referralCode(UUID.randomUUID().toString().substring(0, 10)).build());

        assertThatThrownBy(() -> authService.signInWithGoogle(request()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void offUntilConfigured() {
        when(googleIdTokens.enabled()).thenReturn(false);

        assertThatThrownBy(() -> authService.signInWithGoogle(request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static GoogleSignInRequest request() {
        return new GoogleSignInRequest("credential", null, null, "tiktok", "video", null, null, null);
    }
}
