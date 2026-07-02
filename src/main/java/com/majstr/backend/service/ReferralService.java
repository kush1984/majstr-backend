package com.majstr.backend.service;

import com.majstr.backend.entity.Partner;
import com.majstr.backend.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the first-touch referral source at registration. Both a {@code ref}
 * (from a partner link) and a {@code promoCode} (typed) map to a partner via the
 * {@link PartnerRepository} registry (data, not hardcode). The ref wins (a link
 * is the strongest first-touch signal), then the promo code, else DIRECT.
 *
 * <p>A rare conflict (one partner's link + another's code) is intentionally NOT
 * automated — the ref-wins rule records it as it arrived; the admin can correct
 * it by hand. The source is stamped once and never auto-changed afterwards.</p>
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    public static final String DIRECT = "DIRECT";

    private final PartnerRepository partnerRepository;

    @Transactional(readOnly = true)
    public String resolveSource(String ref, String promoCode) {
        String fromRef = sourceOf(ref);
        if (fromRef != null) {
            return fromRef;
        }
        String fromCode = sourceOf(promoCode);
        if (fromCode != null) {
            return fromCode;
        }
        return DIRECT;
    }

    /** The partner source for an active code, or null if blank/unknown/inactive. */
    private String sourceOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return partnerRepository.findByCodeIgnoreCaseAndActiveTrue(code.trim())
                .map(Partner::getSource)
                .orElse(null);
    }
}
