package com.majstr.backend.service;

import com.majstr.backend.repository.EmailVerificationTokenRepository;
import com.majstr.backend.repository.PasswordResetTokenRepository;
import com.majstr.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenCleanupServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock EmailVerificationTokenRepository verificationTokenRepository;
    @Mock PasswordResetTokenRepository passwordResetTokenRepository;
    @InjectMocks TokenCleanupService service;

    @Test
    void purge_deletesDeadRefreshAndExpiredVerificationAndResetTokens() {
        given(refreshTokenRepository.deleteExpiredOrRevoked(any(Instant.class))).willReturn(3);
        given(verificationTokenRepository.deleteExpired(any(Instant.class))).willReturn(2);
        // The password-reset sweep joined the same daily job — reset tokens are bearer
        // credentials, so they must not outlive their TTL in the table either.
        given(passwordResetTokenRepository.deleteExpired(any(Instant.class))).willReturn(1);

        service.purgeDeadTokens();

        verify(refreshTokenRepository).deleteExpiredOrRevoked(any(Instant.class));
        verify(verificationTokenRepository).deleteExpired(any(Instant.class));
        verify(passwordResetTokenRepository).deleteExpired(any(Instant.class));
    }
}
