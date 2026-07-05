package com.majstr.backend.service;

import com.majstr.backend.entity.Partner;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PartnerRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves the first-touch referral attribution at registration and mints each
 * master's personal referral code.
 *
 * <p>Two independent dimensions share the {@code ref}/{@code promoCode} inputs:
 * <ul>
 *   <li><b>Master→master</b> — a personal code shaped {@code m-<referralCode>}
 *       resolves to source {@code MASTER} + the inviting user's id (drives the
 *       first-payment reward).</li>
 *   <li><b>Partner</b> — a partner code (LIGA, …) resolves via the
 *       {@link PartnerRepository} registry to that partner's source, exactly as
 *       before. Partner attribution / rev-share is untouched by the master codes.</li>
 * </ul>
 * The {@code ref} wins over the {@code promoCode} (a link is the strongest
 * first-touch signal); within one value a master code is tried before a partner
 * code. Anything else is {@code DIRECT}. Stamped once, never auto-changed.</p>
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    public static final String DIRECT = "DIRECT";
    public static final String MASTER = "MASTER";
    private static final String MASTER_PREFIX = "m-";
    private static final String CODE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 8;

    private final PartnerRepository partnerRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    /** Resolved first-touch attribution: a source string plus, for a master code,
     *  the inviting user's id (null for partner / DIRECT). */
    public record Attribution(String source, UUID referredByUserId) {}

    @Transactional(readOnly = true)
    public Attribution resolve(String ref, String promoCode) {
        Attribution fromRef = attributionOf(ref);
        if (fromRef != null) {
            return fromRef;
        }
        Attribution fromCode = attributionOf(promoCode);
        if (fromCode != null) {
            return fromCode;
        }
        return new Attribution(DIRECT, null);
    }

    /** A master's {@code m-<code>} or a partner code → attribution, or null if the
     *  value is blank / an unknown code (so the caller falls through). */
    private Attribution attributionOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim();
        if (code.regionMatches(true, 0, MASTER_PREFIX, 0, MASTER_PREFIX.length())) {
            String personal = code.substring(MASTER_PREFIX.length()).toLowerCase(Locale.ROOT);
            return userRepository.findByReferralCode(personal)
                    .map(u -> new Attribution(MASTER, u.getId()))
                    .orElse(null);
        }
        return partnerRepository.findByCodeIgnoreCaseAndActiveTrue(code)
                .map(Partner::getSource)
                .map(source -> new Attribution(source, null))
                .orElse(null);
    }

    /** A fresh, unique, URL-safe personal referral code (retry on the rare collision). */
    @Transactional(readOnly = true)
    public String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            if (!userRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        // Astronomically unlikely; fall back to a UUID-derived code (still checked by the UNIQUE column).
        return UUID.randomUUID().toString().replace("-", "").substring(0, CODE_LENGTH);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
