package com.majstr.backend.service;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final CatalogTemplateService catalogTemplateService;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName().trim())
                .trades(new LinkedHashSet<>(req.trades()))
                .phone(req.phone().trim())
                .companyName(req.companyName().trim())
                // Consent is required (@AssertTrue on the request) — stamp it.
                .consentedToPrivacyAt(Instant.now())
                .build();
        user = userRepository.save(user);
        // Copy starter catalog templates for every chosen trade (merged,
        // de-duplicated) so they never see an empty library on first login.
        catalogTemplateService.seedForUser(user);
        // Issue a verification token and email it (async, fail-soft — a mail
        // problem must not break registration; the user can resend later).
        emailVerificationService.issueAndSend(user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmailIgnoreCase(req.email().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(refreshToken);
        String access = jwtService.generateAccessToken(rotated.user().getId(), rotated.user().getEmail());
        return AuthResponse.of(access, rotated.newRefreshToken(), jwtService.accessTtlSeconds(), UserResponse.from(rotated.user()));
    }

    @Transactional
    public void logout(String refreshToken) {
        // Invalidate the refresh token server-side so it can't outlive the session.
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse issueTokens(User user) {
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refresh = refreshTokenService.issue(user);
        return AuthResponse.of(access, refresh, jwtService.accessTtlSeconds(), UserResponse.from(user));
    }
}
