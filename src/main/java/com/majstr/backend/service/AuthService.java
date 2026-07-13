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
    private final ReferralService referralService;
    private final EmailPolicyService emailPolicyService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase().trim();
        // Anti-abuse: reject disposable/no-mail domains (fail-open on DNS), and dedupe
        // on the canonical form so gmail aliases can't spawn parallel accounts.
        emailPolicyService.assertAcceptable(email);
        String canonical = emailPolicyService.canonicalize(email);
        if (userRepository.existsByEmailIgnoreCase(email) || userRepository.existsByEmailCanonical(canonical)) {
            throw new EmailAlreadyExistsException(email);
        }
        // First-touch attribution (ref link wins, then promo code, else DIRECT).
        // A master's m-<code> also yields the inviting user's id. Stamped once here;
        // only an admin can change it later.
        ReferralService.Attribution attribution = referralService.resolve(req.ref(), req.promoCode());
        User user = User.builder()
                .email(email)
                .emailCanonical(canonical)
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName().trim())
                .trades(new LinkedHashSet<>(req.trades()))
                .phone(req.phone().trim())
                .companyName(req.companyName().trim())
                // Consent is required (@AssertTrue on the request) — stamp it.
                .consentedToPrivacyAt(Instant.now())
                .referralSource(attribution.source())
                .referredByUserId(attribution.referredByUserId())
                // This master's own shareable code (majstr.pro/?ref=m-<code>).
                .referralCode(referralService.generateUniqueCode())
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
