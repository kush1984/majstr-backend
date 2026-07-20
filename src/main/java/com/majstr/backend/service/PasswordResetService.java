package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.PasswordResetToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidPasswordResetTokenException;
import com.majstr.backend.repository.PasswordResetTokenRepository;
import com.majstr.backend.repository.RefreshTokenRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Password reset — mirrors {@link EmailVerificationService}. Crypto-random single-use token,
 * short TTL. {@code requestReset} is anti-enumeration (never reveals whether the email exists);
 * {@code reset} sets the new password, consumes the token, and revokes every refresh token
 * (a reset logs out all sessions — a stolen/forgotten password can't keep an old session alive).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final Duration TTL = Duration.ofMinutes(45); // shorter than email verification
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** Bundle key — resolved to the user's language by GlobalExceptionHandler. */
    private static final String INVALID_MSG = "error.password-reset.invalid";

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    /**
     * Mint a token and email a reset link — only if the address belongs to a real account.
     * If it doesn't, this is a silent no-op: the caller returns the same neutral 200 either
     * way, so an attacker can't tell whether an account exists.
     */
    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            // A fresh request supersedes any pending token for this user.
            tokenRepository.deleteByUserId(user.getId());
            PasswordResetToken token = tokenRepository.save(PasswordResetToken.builder()
                    .user(user)
                    .token(generateToken())
                    .expiresAt(Instant.now().plus(TTL))
                    .build());
            emailService.sendPasswordResetEmail(user, token.getToken());
        });
    }

    /**
     * Consume the token and set the new password. Bad/expired/used token →
     * {@link InvalidPasswordResetTokenException} (400 {@code INVALID_OR_EXPIRED_TOKEN}).
     * All refresh tokens are revoked so existing sessions can't survive the reset.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new InvalidPasswordResetTokenException(INVALID_MSG));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidPasswordResetTokenException(INVALID_MSG);
        }
        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        token.setUsedAt(Instant.now());
        int revoked = refreshTokenRepository.revokeAllForUser(user.getId());
        log.info("Password reset for user {} — {} session(s) revoked", user.getId(), revoked);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
