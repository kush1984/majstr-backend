package com.majstr.backend.service;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserTradeRepository userTradeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final CatalogTemplateService catalogTemplateService;
    private final EmailVerificationService emailVerificationService;
    private final ReferralService referralService;
    private final EmailPolicyService emailPolicyService;
    private final ProfileService profileService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase().trim();
        // Anti-abuse: reject disposable/no-mail domains (fail-open on DNS), and dedupe
        // on the canonical form so gmail aliases can't spawn parallel accounts.
        emailPolicyService.assertAcceptable(email);
        String canonical = emailPolicyService.canonicalize(email);
        // Serialise same-canonical registrations before the check: otherwise a burst of
        // gmail aliases all read "not taken" together and every one of them gets an account,
        // which is precisely the abuse the canonical column exists to stop.
        userRepository.lockCanonicalEmail(canonical);
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
        createCustomTrades(user, req.customTrades());
        // Copy starter catalog templates for every chosen trade (merged,
        // de-duplicated) so they never see an empty library on first login.
        catalogTemplateService.seedForUser(user);
        // Issue a verification token and email it (async, fail-soft — a mail
        // problem must not break registration; the user can resend later).
        emailVerificationService.issueAndSend(user);
        return issueTokens(user);
    }

    /**
     * A master may type the same custom trade name twice on the register form (or paste it in
     * twice) — that must merge silently into one, not surface {@code ProfileService}'s 409 for a
     * brand-new account that owns none yet. Dedup case-insensitively within THIS request before
     * delegating to the shared create logic.
     */
    private void createCustomTrades(User user, List<String> names) {
        if (names == null) {
            return;
        }
        var seen = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            if (seen.add(trimmed)) {
                profileService.createCustomTrade(user, trimmed);
            }
        }
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
        List<UserTrade> customTrades =
                userTradeRepository.findByUserIdOrderBySortOrderAscIdAsc(rotated.user().getId());
        return AuthResponse.of(access, rotated.newRefreshToken(), jwtService.accessTtlSeconds(),
                UserResponse.from(rotated.user(), customTrades));
    }

    @Transactional
    public void logout(String refreshToken) {
        // Invalidate the refresh token server-side so it can't outlive the session.
        refreshTokenService.revoke(refreshToken);
    }

    private AuthResponse issueTokens(User user) {
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refresh = refreshTokenService.issue(user);
        // A brand-new registration has no custom trades yet; login/refresh reuse this helper too,
        // so look them up rather than assuming empty.
        List<UserTrade> customTrades =
                userTradeRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId());
        return AuthResponse.of(access, refresh, jwtService.accessTtlSeconds(), UserResponse.from(user, customTrades));
    }
}
