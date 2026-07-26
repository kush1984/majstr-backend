package com.majstr.backend.service;

import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.entity.RefreshToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidTokenException;
import com.majstr.backend.repository.RefreshTokenRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository repository;
    @Mock UserRepository userRepository;
    @Mock JwtProperties jwtProperties;
    @InjectMocks RefreshTokenService service;

    private final UUID userId = UUID.randomUUID();
    private final User user = User.builder().id(userId).email("ivan@example.com").build();

    private RefreshToken stored(boolean revoked, Instant expiresAt) {
        return stored(revoked, expiresAt, null);
    }

    /** {@code rotatedAt != null} marks a token revoked BY ROTATION — the only kind that is graced. */
    private RefreshToken stored(boolean revoked, Instant expiresAt, Instant rotatedAt) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash("some-hash")
                .expiresAt(expiresAt)
                .revoked(revoked)
                .rotatedAt(rotatedAt)
                .build();
    }

    @Test
    void issue_storesHashedTokenNotRaw() {
        given(jwtProperties.refreshTokenExpirationDays()).willReturn(30L);

        String raw = service.issue(user);

        assertThat(raw).isNotBlank();
        ArgumentCaptor<RefreshToken> cap = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(cap.capture());
        RefreshToken saved = cap.getValue();
        assertThat(saved.getTokenHash()).isNotBlank().isNotEqualTo(raw); // stored hashed, never raw
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void rotate_revokesOldAndIssuesNewPair() {
        RefreshToken old = stored(false, Instant.now().plus(Duration.ofDays(5)));
        given(repository.findByTokenHash(anyString())).willReturn(Optional.of(old));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtProperties.refreshTokenExpirationDays()).willReturn(30L);

        RefreshTokenService.RotationResult result = service.rotate("old-raw");

        assertThat(old.isRevoked()).isTrue();               // old invalidated
        // Stamped as rotated (not logged out) — this is what earns the replay grace below.
        assertThat(old.getRotatedAt()).isNotNull();
        assertThat(result.user()).isEqualTo(user);
        assertThat(result.newRefreshToken()).isNotBlank();
        // Two saves: the revoked old row + the freshly issued one.
        verify(repository, org.mockito.Mockito.times(2)).save(any(RefreshToken.class));
    }

    @Test
    void rotate_revokedByLogout_getsNoGrace() {
        // rotatedAt == null means "revoked, but not by rotation" — i.e. the user logged out.
        // That must die immediately; the grace window is only for a rotation whose response
        // may never have reached the client.
        given(repository.findByTokenHash(anyString()))
                .willReturn(Optional.of(stored(true, Instant.now().plus(Duration.ofDays(5)))));
        given(jwtProperties.refreshRotationGraceSeconds()).willReturn(60L);

        assertThatThrownBy(() -> service.rotate("old-raw"))
                .isInstanceOf(InvalidTokenException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void rotate_replayedWithinGrace_issuesAFreshPairInsteadOfEndingTheSession() {
        // THE bug this exists for: the rotation reached the server, the response did not (a
        // master in a lift / basement). The client still holds the old token; strict single-use
        // would 401, and the PWA reads that as "auth is dead" — logging them out AND wiping
        // their unsynced offline queue.
        RefreshToken rotatedSecondsAgo = stored(true, Instant.now().plus(Duration.ofDays(5)),
                Instant.now().minus(Duration.ofSeconds(5)));
        given(repository.findByTokenHash(anyString())).willReturn(Optional.of(rotatedSecondsAgo));
        given(jwtProperties.refreshRotationGraceSeconds()).willReturn(60L);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(jwtProperties.refreshTokenExpirationDays()).willReturn(30L);

        RefreshTokenService.RotationResult result = service.rotate("old-raw");

        assertThat(result.newRefreshToken()).isNotBlank();
        assertThat(result.user()).isEqualTo(user);
        // Only the NEW row is written: the replay must not re-stamp rotatedAt, or a client
        // stuck in a retry loop would keep pushing the window forward indefinitely.
        verify(repository, org.mockito.Mockito.times(1)).save(any(RefreshToken.class));
        assertThat(rotatedSecondsAgo.getRotatedAt()).isBefore(Instant.now().minus(Duration.ofSeconds(4)));
    }

    @Test
    void rotate_replayedAfterTheGraceExpired_isRejected() {
        RefreshToken rotatedLongAgo = stored(true, Instant.now().plus(Duration.ofDays(5)),
                Instant.now().minus(Duration.ofMinutes(10)));
        given(repository.findByTokenHash(anyString())).willReturn(Optional.of(rotatedLongAgo));
        given(jwtProperties.refreshRotationGraceSeconds()).willReturn(60L);

        assertThatThrownBy(() -> service.rotate("old-raw"))
                .isInstanceOf(InvalidTokenException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void rotate_expiredTokenGetsNoGraceEvenIfJustRotated() {
        // Expiry is absolute. A 30-day-old session does not get to live 60s longer because it
        // happened to be rotated on its way out.
        given(repository.findByTokenHash(anyString()))
                .willReturn(Optional.of(stored(true, Instant.now().minus(Duration.ofSeconds(1)),
                        Instant.now().minus(Duration.ofSeconds(2)))));

        assertThatThrownBy(() -> service.rotate("old-raw"))
                .isInstanceOf(InvalidTokenException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void rotate_expiredToken_isRejected() {
        given(repository.findByTokenHash(anyString()))
                .willReturn(Optional.of(stored(false, Instant.now().minus(Duration.ofMinutes(1)))));

        assertThatThrownBy(() -> service.rotate("old-raw"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rotate_unknownToken_isRejected() {
        given(repository.findByTokenHash(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("nope"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revoke_marksStoredTokenRevoked() {
        RefreshToken token = stored(false, Instant.now().plus(Duration.ofDays(5)));
        given(repository.findByTokenHash(anyString())).willReturn(Optional.of(token));

        service.revoke("raw");

        assertThat(token.isRevoked()).isTrue();
        verify(repository).save(token);
    }

    @Test
    void revoke_unknownToken_isNoop() {
        given(repository.findByTokenHash(anyString())).willReturn(Optional.empty());

        service.revoke("raw"); // must not throw

        verify(repository, never()).save(any());
    }

    @Test
    void revoke_blankToken_isNoop() {
        service.revoke("  ");

        verify(repository, never()).findByTokenHash(any());
        verify(repository, never()).save(any());
    }
}
