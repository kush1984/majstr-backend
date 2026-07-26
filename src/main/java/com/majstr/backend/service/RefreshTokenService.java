package com.majstr.backend.service;

import com.majstr.backend.config.JwtProperties;
import com.majstr.backend.entity.RefreshToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidTokenException;
import com.majstr.backend.repository.RefreshTokenRepository;
import com.majstr.backend.repository.UserRepository;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 48;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    @Transactional
    public String issue(User user) {
        String raw = generateRawToken();
        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hash(raw))
                .expiresAt(Instant.now().plus(Duration.ofDays(jwtProperties.refreshTokenExpirationDays())))
                .revoked(false)
                .build();
        repository.save(entity);
        return raw;
    }

    /**
     * Invalidates a single refresh token (logout). Idempotent and silent: an
     * unknown or already-revoked token is a no-op, so logout always succeeds and
     * never reveals whether a token existed.
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            token.setRevoked(true);
            repository.save(token);
        });
    }

    /**
     * Exchanges a refresh token for a new pair, revoking the old one (rotation).
     *
     * <p><b>Grace window.</b> The client only stores the replacement once the response lands,
     * so a reply lost in transit leaves it holding a token the server already revoked — and
     * the next attempt would end the session and, in the PWA, discard the master's unsynced
     * offline queue. A token rotated within {@code app.jwt.refresh-rotation-grace-seconds} is
     * therefore accepted once more and issues a fresh pair. Any replacement already handed out
     * stays valid: re-revoking it would just move the logout to whichever tab held it.
     *
     * <p>Logout is deliberately NOT covered — {@link #revoke} leaves {@code rotatedAt} null, so
     * a token the user logged out of dies immediately.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken stored = repository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or revoked"));

        Instant now = Instant.now();
        boolean replayedWithinGrace = false;
        if (!stored.isUsable(now)) {
            // Expiry is absolute; only a recent ROTATION earns a second chance.
            if (!stored.getExpiresAt().isAfter(now)
                    || !stored.isWithinRotationGrace(now, Duration.ofSeconds(jwtProperties.refreshRotationGraceSeconds()))) {
                throw new InvalidTokenException("Refresh token is invalid or expired");
            }
            replayedWithinGrace = true;
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Refresh token owner not found"));

        if (replayedWithinGrace) {
            // Already revoked and stamped by the first rotation — leave both as they are so the
            // window stays anchored to the ORIGINAL rotation and cannot be extended by replays.
            log.debug("Refresh token replayed within the rotation grace window for user {}", user.getId());
        } else {
            stored.setRevoked(true);
            stored.setRotatedAt(now);
            repository.save(stored);
        }

        String newRaw = issue(user);
        return new RotationResult(user, newRaw);
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
