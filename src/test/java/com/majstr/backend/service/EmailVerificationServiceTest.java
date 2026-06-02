package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.EmailVerificationToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidVerificationTokenException;
import com.majstr.backend.repository.EmailVerificationTokenRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock EmailVerificationTokenRepository tokenRepository;
    @Mock UserRepository userRepository;
    @Mock EmailService emailService;
    @InjectMocks EmailVerificationService service;

    @Test
    void issueAndSend_savesTokenAndSendsEmail() {
        User user = User.builder().id(UUID.randomUUID()).email("a@b.com").fullName("Іван").build();

        service.issueAndSend(user);

        ArgumentCaptor<EmailVerificationToken> cap = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(cap.capture());
        EmailVerificationToken saved = cap.getValue();
        assertThat(saved.getToken()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        verify(emailService).sendVerificationEmail(eq(user), eq(saved.getToken()));
    }

    @Test
    void verify_validToken_marksUserVerified() {
        User user = User.builder().id(UUID.randomUUID()).emailVerified(false).build();
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user).token("tok").expiresAt(Instant.now().plusSeconds(3600)).build();
        given(tokenRepository.findByToken("tok")).willReturn(Optional.of(token));

        service.verify("tok");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void verify_expiredToken_throwsAndLeavesUserUnverified() {
        User user = User.builder().id(UUID.randomUUID()).emailVerified(false).build();
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user).token("old").expiresAt(Instant.now().minusSeconds(60)).build();
        given(tokenRepository.findByToken("old")).willReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify("old"))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void verify_alreadyUsedToken_throws() {
        User user = User.builder().id(UUID.randomUUID()).emailVerified(false).build();
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user).token("used").expiresAt(Instant.now().plusSeconds(3600))
                .usedAt(Instant.now().minusSeconds(10)).build();
        given(tokenRepository.findByToken("used")).willReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify("used"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void verify_unknownToken_throws() {
        given(tokenRepository.findByToken("nope")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify("nope"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }
}
