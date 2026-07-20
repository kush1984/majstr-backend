package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.PasswordResetToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidPasswordResetTokenException;
import com.majstr.backend.repository.PasswordResetTokenRepository;
import com.majstr.backend.repository.RefreshTokenRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock EmailService emailService;
    @Mock PasswordEncoder passwordEncoder;

    private PasswordResetService service() {
        return new PasswordResetService(tokenRepository, userRepository, refreshTokenRepository,
                emailService, passwordEncoder);
    }

    @Test
    void requestReset_knownEmail_dropsOldTokensMintsAndSends() {
        User user = User.builder().id(UUID.randomUUID()).email("a@b.com").fullName("Іван").build();
        given(userRepository.findByEmailIgnoreCase("a@b.com")).willReturn(Optional.of(user));
        given(tokenRepository.save(any(PasswordResetToken.class))).willAnswer(inv -> inv.getArgument(0));

        service().requestReset("  a@b.com ");

        verify(tokenRepository).deleteByUserId(user.getId()); // supersede pending
        ArgumentCaptor<PasswordResetToken> cap = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(cap.capture());
        PasswordResetToken saved = cap.getValue();
        assertThat(saved.getToken()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        verify(emailService).sendPasswordResetEmail(eq(user), eq(saved.getToken()));
    }

    @Test
    void requestReset_unknownEmail_isASilentNoOp() {
        given(userRepository.findByEmailIgnoreCase("ghost@b.com")).willReturn(Optional.empty());

        service().requestReset("ghost@b.com"); // must not throw (anti-enumeration)

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any());
    }

    @Test
    void reset_validToken_setsHashConsumesTokenAndRevokesSessions() {
        User user = User.builder().id(UUID.randomUUID()).passwordHash("OLD").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("tok").expiresAt(Instant.now().plusSeconds(600)).build();
        given(tokenRepository.findByToken("tok")).willReturn(Optional.of(token));
        given(passwordEncoder.encode("newPass123")).willReturn("HASHED");

        service().reset("tok", "newPass123");

        assertThat(user.getPasswordHash()).isEqualTo("HASHED");
        assertThat(token.getUsedAt()).isNotNull();          // single-use
        verify(refreshTokenRepository).revokeAllForUser(user.getId()); // all sessions out
    }

    @Test
    void reset_unknownToken_throws() {
        given(tokenRepository.findByToken("nope")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service().reset("nope", "newPass123"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        verify(refreshTokenRepository, never()).revokeAllForUser(any());
    }

    @Test
    void reset_expiredToken_throwsAndLeavesPasswordUnchanged() {
        User user = User.builder().id(UUID.randomUUID()).passwordHash("OLD").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("old").expiresAt(Instant.now().minusSeconds(60)).build();
        given(tokenRepository.findByToken("old")).willReturn(Optional.of(token));

        assertThatThrownBy(() -> service().reset("old", "newPass123"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
        assertThat(user.getPasswordHash()).isEqualTo("OLD");
        verify(refreshTokenRepository, never()).revokeAllForUser(any());
    }

    @Test
    void reset_alreadyUsedToken_throws() {
        User user = User.builder().id(UUID.randomUUID()).passwordHash("OLD").build();
        PasswordResetToken token = PasswordResetToken.builder()
                .user(user).token("used").expiresAt(Instant.now().plusSeconds(600))
                .usedAt(Instant.now().minusSeconds(10)).build();
        given(tokenRepository.findByToken("used")).willReturn(Optional.of(token));

        assertThatThrownBy(() -> service().reset("used", "newPass123"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);
    }
}
