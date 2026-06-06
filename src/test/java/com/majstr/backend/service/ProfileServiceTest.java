package com.majstr.backend.service;

import com.majstr.backend.dto.ProfileUpdateRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository userRepository;
    @Mock StorageService storage;                       // unused here, needed for @InjectMocks
    @Mock EmailVerificationService emailVerificationService;
    @InjectMocks ProfileService profileService;

    private final UUID userId = UUID.randomUUID();

    private User user(boolean verified, String email) {
        return User.builder()
                .id(userId)
                .email(email)
                .emailVerified(verified)
                .fullName("Старе Ім'я").phone("+380000000000").companyName("Стара ФОП")
                .trades(new LinkedHashSet<>(Set.of(Trade.ELECTRICAL)))
                .passwordHash("x")
                .build();
    }

    private ProfileUpdateRequest req(String email) {
        return new ProfileUpdateRequest("Іван Новий", "+380671112233", "Нова Компанія",
                Set.of(Trade.TILING, Trade.PLUMBING), email);
    }

    @Test
    void update_savesBasicFieldsAndReplacesTrades() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.updateProfile(userId, req("ivan@example.com"));

        assertThat(u.getFullName()).isEqualTo("Іван Новий");
        assertThat(u.getPhone()).isEqualTo("+380671112233");
        assertThat(u.getCompanyName()).isEqualTo("Нова Компанія");
        assertThat(u.getTrades()).containsExactlyInAnyOrder(Trade.TILING, Trade.PLUMBING);
        assertThat(resp.fullName()).isEqualTo("Іван Новий");
        // Email unchanged (same value) → no re-verification.
        verify(emailVerificationService, never()).replaceForNewEmail(any());
    }

    @Test
    void update_unverifiedEmailChanged_setsEmailAndReissuesVerification() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(userRepository.existsByEmailIgnoreCase("new@example.com")).willReturn(false);

        profileService.updateProfile(userId, req("New@Example.com"));

        assertThat(u.getEmail()).isEqualTo("new@example.com"); // normalized
        assertThat(u.isEmailVerified()).isFalse();
        verify(emailVerificationService).replaceForNewEmail(u);
    }

    @Test
    void update_unverifiedEmailTaken_throwsConflictAndKeepsOldEmail() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(userRepository.existsByEmailIgnoreCase("taken@example.com")).willReturn(true);

        assertThatThrownBy(() -> profileService.updateProfile(userId, req("taken@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        assertThat(u.getEmail()).isEqualTo("old@example.com");
        verify(emailVerificationService, never()).replaceForNewEmail(any());
    }

    @Test
    void update_verifiedEmail_changeIgnoredButRestSaved() {
        User u = user(true, "verified@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, req("hacker@example.com"));

        assertThat(u.getEmail()).isEqualTo("verified@example.com"); // locked, ignored
        assertThat(u.getFullName()).isEqualTo("Іван Новий");        // rest still saved
        verify(emailVerificationService, never()).replaceForNewEmail(any());
        // Uniqueness is never even checked for a locked email.
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
    }
}
