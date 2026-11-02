package com.majstr.backend.service;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.entity.RefreshToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidTokenException;
import com.majstr.backend.repository.RefreshTokenRepository;
import com.majstr.backend.repository.UserRepository;
import io.sentry.SentryLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 48;
    private static final short MAX_GRACE_USES = 2;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;
    private final RefreshTokenFamilies families;

    /** A fresh session: its token starts a family of its own. */
    @Transactional
    public String issue(User user) {
        return issue(user, null);
    }

    private String issue(User user, UUID familyId) {
        String raw = generateRawToken();
        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .familyId(familyId)
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(Duration.ofDays(jwtProperties.refreshTokenExpirationDays())))
                .revoked(false)
                .build();
        repository.save(entity);
        return raw;
    }

    /**
     * Ends the session this token belongs to (logout), its whole rotation chain included: a token
     * rotated a moment ago could otherwise still mint a new one. Idempotent and silent: an unknown
     * token is a no-op, so logout always succeeds and never reveals whether a token existed.
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> repository.revokeFamily(token.getFamilyId()));
    }

    /**
     * Exchanges a refresh token for a new pair, revoking the old one (rotation).
     *
     * <p><b>Grace window.</b> The client only stores the replacement once the response lands,
     * so a reply lost in transit leaves it holding a token the server already revoked — and
     * the next attempt would end the session and, in the PWA, discard the master's unsynced
     * offline queue. A token rotated within {@code app.jwt.refresh-rotation-grace-seconds} is
     * therefore accepted again and issues a fresh pair, at most {@value #MAX_GRACE_USES} times:
     * enough for tabs racing on one wake-up, and no open door for a stolen token. Any replacement
     * already handed out stays valid: re-revoking it would just move the logout to whichever tab
     * held it. The window stays anchored to the ORIGINAL rotation, so replays cannot extend it.
     *
     * <p><b>Past the window a rotated token is a replay nobody legitimate has reason to make</b>,
     * while the chain it belonged to is live somewhere (review B-126). The whole family goes, so a
     * thief who rotated first does not keep a session for 30 days. A master whose reply was lost
     * and who came back minutes later was being logged out here anyway.
     *
     * <p>Logout and a password reset are deliberately NOT forgiven: both clear {@code rotatedAt},
     * so those tokens die immediately.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or revoked"));

        Instant now = Instant.now();
        // Expiry is absolute; only a recent ROTATION earns a second chance.
        if (!stored.getExpiresAt().isAfter(now)) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
        if (stored.isRevoked()) {
            refuseUnlessWithinGrace(stored, now);
        } else if (repository.markRotated(stored.getId(), now) == 0) {
            spendGrace(stored); // another request rotated it a moment ago
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Refresh token owner not found"));
        return new RotationResult(user, issue(user, stored.getFamilyId()));
    }

    private void refuseUnlessWithinGrace(RefreshToken stored, Instant now) {
        if (stored.getRotatedAt() == null) {
            throw new InvalidTokenException("Refresh token is invalid or revoked");
        }
        if (!stored.isWithinRotationGrace(now, Duration.ofSeconds(jwtProperties.refreshRotationGraceSeconds()))) {
            families.revoke(stored.getFamilyId());
            log.warn("A rotated refresh token was replayed for user {}: its session is ended", stored.getUserId());
            BackgroundErrors.capture("refresh-token-replay",
                    new IllegalStateException("A rotated refresh token was replayed"), SentryLevel.WARNING);
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
        spendGrace(stored);
    }

    private void spendGrace(RefreshToken stored) {
        if (jwtProperties.refreshRotationGraceSeconds() == 0
                || repository.spendGrace(stored.getId(), MAX_GRACE_USES) == 0) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RotationResult(User user, String newRefreshToken) {}
}
