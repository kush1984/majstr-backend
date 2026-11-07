package com.majstr.backend.service;

import com.majstr.backend.dto.HelpRequest;
import com.majstr.backend.dto.ProfileTradesRequest;
import com.majstr.backend.dto.ProfileUpdateRequest;
import com.majstr.backend.dto.SenderProfileRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.CustomTradeDuplicateException;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.exception.ProfileNameRequiredException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

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
    private final PlatformTransactionManager transactionManager;
    private final AbuseLimits abuseLimits;
    private final CatalogTemplateService catalogTemplateService;

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

    /**
     * The address check comes before the transaction, since it can wait on DNS for seconds, and so does
     * the limit on changes: each one mails a new address, which the resend limiter never sees (B-127).
     */
    public UserResponse updateProfile(UUID userId, ProfileUpdateRequest req) {
        User current = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        String changedEmail = changedEmail(current, req.email());
        if (changedEmail != null) {
            emailPolicyService.assertAcceptable(changedEmail);
            abuseLimits.requireEmailChange(userId);
        }
        return Transactions.write(transactionManager, () -> saveProfile(userId, req));
    }

    private UserResponse saveProfile(UUID userId, ProfileUpdateRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        // Optional since V190 — blank is NULL, see ContractorName — but only until it is first given:
        // documents may already carry it, and one signed after an erase would freeze no contractor.
        String fullName = trimToNull(req.fullName());
        if (fullName == null && ContractorName.hasName(user)) {
            throw new ProfileNameRequiredException();
        }
        user.setFullName(fullName);
        user.setPhone(trimToNull(req.phone()));
        user.setCompanyName(trimToNull(req.companyName()));
        replaceTrades(user, req.trades());
        applyEmailChange(user, req.email());
        applyRequisites(user, req);
        return response(user);
    }

    /** The «Чим займаєтесь?» screen (V190): the system trades alone, an empty set included. */
    @Transactional
    public UserResponse updateTrades(UUID userId, ProfileTradesRequest req) {
        User user = loadUser(userId);
        replaceTrades(user, req.trades());
        return response(user);
    }

    /** «Як вас підписати в документі?» (V190): the three fields a document prints, nothing else. */
    @Transactional
    public UserResponse updateSender(UUID userId, SenderProfileRequest req) {
        User user = loadUser(userId);
        user.setFullName(req.fullName().trim());
        user.setPhone(trimToNull(req.phone()));
        user.setCompanyName(trimToNull(req.companyName()));
        return response(user);
    }

    /**
     * «Залиште телефон — передзвонимо» (V191): the number is kept apart from the profile's phone (V193),
     * and the first time he asks is kept — asking again only updates the number and reopens the call.
     */
    @Transactional
    public UserResponse requestHelp(UUID userId, HelpRequest req) {
        User user = loadUser(userId);
        // Its own column (V193): a number left for one call is not the one documents print.
        user.setHelpPhone(req.phone().trim());
        if (user.getHelpRequestedAt() == null) {
            user.setHelpRequestedAt(Instant.now());
        }
        user.setHelpHandledAt(null);
        return response(user);
    }

    /**
     * A trade the master did not have before brings its starter catalog with it, on every door that
     * sets trades. The profile used to ASK («додати набір?»), and «Не зараз» left a trade whose
     * templates showed up and then priced every line at 0 ₴. The merge never overwrites or duplicates
     * (name + type + unit), so filling unasked is safe. Removing a trade never touches the catalog —
     * those items are his once copied, and a set down to none (custom trades only) is a supported
     * end state.
     */
    private void replaceTrades(User user, Set<Trade> requested) {
        // Any door that sets trades is an answer to «Чим займаєтесь?» (V193): unticking every trade in
        // the profile later must not send him back to that screen.
        if (user.getTradesOnboardedAt() == null) {
            user.setTradesOnboardedAt(Instant.now());
        }
        Set<Trade> added = new LinkedHashSet<>(requested);
        added.removeAll(user.getTrades());
        user.setTrades(new LinkedHashSet<>(requested));
        if (!added.isEmpty()) {
            catalogTemplateService.addTemplatesForTrades(user, added);
        }
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

    /** The new address, or null when this request changes nothing: blank, the same, or locked as verified. */
    private static String changedEmail(User user, String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank() || user.isEmailVerified()) {
            return null;
        }
        String newEmail = rawEmail.toLowerCase(Locale.ROOT).trim();
        return newEmail.equals(user.getEmail()) ? null : newEmail;
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
        String newEmail = changedEmail(user, rawEmail);
        if (newEmail == null) {
            return;
        }
        String canonical = emailPolicyService.canonicalize(newEmail);
        // Same serialisation as registration, or two parallel changes both read "not taken".
        userRepository.lockCanonicalEmail(canonical);
        if (userRepository.existsByEmailIgnoreCase(newEmail) || userRepository.existsByEmailCanonical(canonical)) {
            throw new EmailAlreadyExistsException(newEmail);
        }
        user.setEmail(newEmail);
        user.setEmailCanonical(canonical);
        emailVerificationService.replaceForNewEmail(user);
    }

    /** Not {@code @Transactional}: the upload is a network round trip with a pooled connection idle (B-121). */
    public String uploadLogo(UUID userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }

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
        ImageBounds.require(content);
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }

        StoredObject stored = storage.store(
                new ByteArrayInputStream(content),
                content.length,
                LOGO_PREFIX,
                kind.extension,
                kind.contentType
        );
        String previous;
        try {
            previous = replaceLogoKey(userId, stored.key());
        } catch (RuntimeException e) {
            tryDelete(stored.key());
            throw e;
        }
        // Remove the old logo to avoid orphaning a file on disk.
        if (previous != null && !previous.isBlank()) {
            tryDelete(previous);
        }
        return stored.key();
    }

    /** Same reason as {@link #uploadLogo}: the row commits first, the old file goes after it. */
    public void deleteLogo(UUID userId) {
        String previous = replaceLogoKey(userId, null);
        if (previous != null && !previous.isBlank()) {
            tryDelete(previous);
        }
    }

    private String replaceLogoKey(UUID userId, String key) {
        return Transactions.write(transactionManager, () -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            String previous = user.getLogoUrl();
            user.setLogoUrl(key);
            return previous;
        });
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored object {}: {}", key, e.getMessage());
        }
    }
}
