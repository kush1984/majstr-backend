package com.majstr.backend.service;

import com.majstr.backend.email.EmailService;
import com.majstr.backend.entity.EmailVerificationToken;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.InvalidVerificationTokenException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EmailVerificationTokenRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int TOKEN_BYTES = 32;
    private static final Duration TTL = Duration.ofHours(24);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String INVALID_MSG =
            "Посилання недійсне або прострочене. Запросіть новий лист.";

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    /** Mint a fresh token for the user and email it (async, fail-soft). Called on register. */
    @Transactional
    public void issueAndSend(User user) {
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user)
                .token(generateToken())
                .expiresAt(Instant.now().plus(TTL))
                .build();
        tokenRepository.save(token);
        emailService.sendVerificationEmail(user, token.getToken());
    }

    /**
     * Email change while still unverified: drop any pending tokens for the user
     * and send a fresh verification to the (already updated) new address.
     */
    @Transactional
    public void replaceForNewEmail(User user) {
        tokenRepository.deleteByUserId(user.getId());
        issueAndSend(user);
    }

    /** Resend for the current user; no-op if already verified. Caller enforces the rate limit. */
    @Transactional
    public void resendFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (!user.isEmailVerified()) {
            issueAndSend(user);
        }
    }

    /** Consume a token and mark its user verified. Bad/expired/used → InvalidVerificationTokenException (400). */
    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByToken(rawToken)
                .orElseThrow(() -> new InvalidVerificationTokenException(INVALID_MSG));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidVerificationTokenException(INVALID_MSG);
        }
        token.setUsedAt(Instant.now());
        token.getUser().setEmailVerified(true);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
