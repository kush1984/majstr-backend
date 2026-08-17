package com.majstr.backend.service;

import com.majstr.backend.dto.ProfileUpdateRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.CustomTradeDuplicateException;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final String LOGO_PREFIX = "logos";
    private static final int HEADER_PEEK_BYTES = 16;
    /** A logo is embedded into every rendered PDF, so it must stay small — and the global
     *  multipart cap (15 MB, sized for photo imports) is nowhere near a sane logo. */
    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    private final UserRepository userRepository;
    private final UserTradeRepository userTradeRepository;
    private final StorageService storage;
    private final EmailVerificationService emailVerificationService;
    private final EmailPolicyService emailPolicyService;

    /** Stamp privacy-policy consent (one-time login modal for users who predate the
     *  registration checkbox). Idempotent — a prior stamp is kept. */
    @Transactional
    public UserResponse recordPrivacyConsent(UUID userId) {
        User user = loadUser(userId);
        if (user.getConsentedToPrivacyAt() == null) {
            user.setConsentedToPrivacyAt(Instant.now());
        }
        return response(user);
    }

    /** Stamp the client-data responsibility acknowledgement (controller/operator
     *  distinction), shown once on first client data. Idempotent. */
    @Transactional
    public UserResponse acknowledgeClientData(UUID userId) {
        User user = loadUser(userId);
        if (user.getAcknowledgedClientDataAt() == null) {
            user.setAcknowledgedClientDataAt(Instant.now());
        }
        return response(user);
    }

    /**
     * Toggle subscription auto-renewal. Disabling is instant (no confirm) and keeps
     * the saved token for a quick re-enable — no charges happen while off. Enabling
     * needs a saved card (from a prior opt-in checkout); without one it stays off and
     * the PWA sends the master to a fresh checkout.
     */
    @Transactional
    public UserResponse setAutoRenew(UUID userId, boolean enabled) {
        User user = loadUser(userId);
        user.setAutoRenew(enabled && user.getCardToken() != null);
        return response(user);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private UserResponse response(User user) {
        return UserResponse.from(user, userTradeRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId()));
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, ProfileUpdateRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone().trim());
        user.setCompanyName(req.companyName().trim());
        // Replacing the trade set never touches the user's catalog items — those
        // are independent once seeded at registration, and removing a system trade
        // (down to none, relying on custom trades instead) is a supported end state.
        user.setTrades(new LinkedHashSet<>(req.trades()));
        applyEmailChange(user, req.email());
        applyRequisites(user, req);
        return response(user);
    }

    /**
     * Document requisites (acts iteration) — all optional. The profile form is a full PUT, so a
     * blank field genuinely means "clear this". The two flag fields are the exception: they are
     * only overwritten when the request actually carries a value, so a caller that omits them (an
     * older client, a partial update) never accidentally resets the master's VAT status or act
     * number format.
     */
    private void applyRequisites(User user, ProfileUpdateRequest req) {
        user.setLegalName(trimToNull(req.legalName()));
        user.setTaxId(trimToNull(req.taxId()));
        user.setLegalAddress(trimToNull(req.legalAddress()));
        user.setIban(trimToNull(req.iban()));
        user.setBankName(trimToNull(req.bankName()));
        user.setVatId(trimToNull(req.vatId()));
        user.setTaxGroup(req.taxGroup());
        user.setTaxRate(req.taxRate());
        user.setDocCity(trimToNull(req.docCity()));
        if (req.vatPayer() != null) {
            user.setVatPayer(req.vatPayer());
        }
        if (req.actNumberFormat() != null) {
            user.setActNumberFormat(req.actNumberFormat());
        }
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ---- custom trades (user_trade) ---------------------------------------

    /** Add a master-invented trade. Idempotent-unfriendly by design (unlike catalog items) —
     *  a repeat name is a mistake worth surfacing (409), not a silent merge; catalog items are
     *  keyed by (name,type,unit) tuples a master edits often, a trade name is typed once. */
    @Transactional
    public UserResponse addCustomTrade(UUID userId, String name) {
        User user = loadUser(userId);
        createCustomTrade(user, name);
        return response(user);
    }

    /**
     * Shared with {@code AuthService.register}, which creates zero or more custom trades in the
     * same transaction as the account — registration dedupes names within its own request before
     * calling this (a repeat name typed twice must merge silently, not 409), so the per-name
     * uniqueness check here only ever fires against trades that predate this call.
     */
    @Transactional
    UserTrade createCustomTrade(User user, String name) {
        String trimmed = name.trim();
        if (userTradeRepository.existsByUserIdAndNameIgnoreCase(user.getId(), trimmed)) {
            throw new CustomTradeDuplicateException(trimmed);
        }
        UserTrade trade = UserTrade.builder()
                .user(user)
                .name(trimmed)
                .sortOrder(userTradeRepository.nextSortOrder(user.getId()))
                .build();
        return userTradeRepository.save(trade);
    }

    /** Rename a custom trade — a live FK, so every position/template filed under it picks up
     *  the new name immediately with no snapshot to update. */
    @Transactional
    public UserResponse renameCustomTrade(UUID userId, UUID tradeId, String name) {
        UserTrade trade = loadOwnedCustomTrade(tradeId, userId);
        String trimmed = name.trim();
        if (!trimmed.equalsIgnoreCase(trade.getName())
                && userTradeRepository.existsByUserIdAndNameIgnoreCase(userId, trimmed)) {
            throw new CustomTradeDuplicateException(trimmed);
        }
        trade.setName(trimmed);
        return response(trade.getUser());
    }

    /**
     * Delete a custom trade. Nothing else to do here: {@code ON DELETE SET NULL} on both
     * {@code catalog_items.custom_trade_id} and {@code estimate_templates.custom_trade_id} drops
     * every position/template filed under it back to plain OTHER at the database level — those
     * rows already carry {@code trade = OTHER} (the invariant both tables enforce), so nothing
     * about them needs to change on this side. Idempotent: an already-gone id is a no-op.
     */
    @Transactional
    public UserResponse deleteCustomTrade(UUID userId, UUID tradeId) {
        User user = loadUser(userId);
        userTradeRepository.findByIdAndUserId(tradeId, userId).ifPresent(userTradeRepository::delete);
        return response(user);
    }

    private UserTrade loadOwnedCustomTrade(UUID tradeId, UUID userId) {
        return userTradeRepository.findByIdAndUserId(tradeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Custom trade not found: " + tradeId));
    }

    /**
     * Email is editable only while unverified (fix a registration typo). A
     * verified email is locked: a different value is ignored and the rest of the
     * profile still saves. Changing an unverified email re-checks uniqueness,
     * keeps the account unverified, drops the old token and sends a fresh
     * verification to the new address.
     */
    private void applyEmailChange(User user, String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String newEmail = rawEmail.toLowerCase().trim();
        if (newEmail.equals(user.getEmail()) || user.isEmailVerified()) {
            return; // unchanged, or locked because already verified
        }
        // Same anti-abuse guards as registration (a typo-fix must not become a bypass).
        emailPolicyService.assertAcceptable(newEmail);
        String canonical = emailPolicyService.canonicalize(newEmail);
        if (userRepository.existsByEmailIgnoreCase(newEmail) || userRepository.existsByEmailCanonical(canonical)) {
            throw new EmailAlreadyExistsException(newEmail);
        }
        user.setEmail(newEmail);
        user.setEmailCanonical(canonical);
        emailVerificationService.replaceForNewEmail(user);
    }

    @Transactional
    public String uploadLogo(UUID userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Logo file is empty");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Read the upload once. Earlier versions peeked the stream and tried to re-open it —
        // MultipartFile gives a fresh stream on every call, so the file ended up with a
        // duplicated 16-byte header on disk.
        byte[] content = file.getBytes();
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        // The comment here used to claim "Spring caps multipart at 2 MB" — it never did, and
        // the global cap has since grown to 15 MB for photo imports. So a 15 MB "logo" was
        // accepted, stored, and then read whole into EVERY rendered PDF. Mirror the explicit
        // check ProjectPhotoService already does.
        if (content.length > MAX_LOGO_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        ImageKind kind = ImageContentTypeDetector.detect(header);

        StoredObject stored = storage.store(
                new ByteArrayInputStream(content),
                content.length,
                LOGO_PREFIX,
                kind.extension,
                kind.contentType
        );
        // Remove the old logo to avoid orphaning a file on disk.
        if (user.getLogoUrl() != null && !user.getLogoUrl().isBlank()) {
            tryDelete(user.getLogoUrl());
        }
        user.setLogoUrl(stored.key());
        return stored.key();
    }

    @Transactional
    public void deleteLogo(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getLogoUrl() == null || user.getLogoUrl().isBlank()) {
            return;
        }
        tryDelete(user.getLogoUrl());
        user.setLogoUrl(null);
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored object {}: {}", key, e.getMessage());
        }
    }
}
