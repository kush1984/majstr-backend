package com.majstr.backend.service;

import com.majstr.backend.dto.ProfileUpdateRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.UserTrade;
import com.majstr.backend.exception.CustomTradeDuplicateException;
import com.majstr.backend.exception.EmailAlreadyExistsException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserTradeRepository userTradeRepository;
    @Mock StorageService storage;                       // unused here, needed for @InjectMocks
    @Mock EmailVerificationService emailVerificationService;
    @Mock EmailPolicyService emailPolicyService;
    @InjectMocks ProfileService profileService;

    private final UUID userId = UUID.randomUUID();

    private User user(boolean verified, String email) {
        return User.builder()
                .id(userId)
                .email(email)
                .emailVerified(verified)
                .fullName("Старе Ім'я").phone("+380000000000").companyName("Стара ФОП")
                .trades(new LinkedHashSet<>(Set.of(Trade.ELECTRICAL)))
                .passwordHash("x")
                .build();
    }

    private ProfileUpdateRequest req(String email) {
        return new ProfileUpdateRequest("Іван Новий", "+380671112233", "Нова Компанія",
                Set.of(Trade.TILING, Trade.PLUMBING), email,
                // No document requisites in the basic request.
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void update_savesBasicFieldsAndReplacesTrades() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.updateProfile(userId, req("ivan@example.com"));

        assertThat(u.getFullName()).isEqualTo("Іван Новий");
        assertThat(u.getPhone()).isEqualTo("+380671112233");
        assertThat(u.getCompanyName()).isEqualTo("Нова Компанія");
        assertThat(u.getTrades()).containsExactlyInAnyOrder(Trade.TILING, Trade.PLUMBING);
        assertThat(resp.fullName()).isEqualTo("Іван Новий");
        // Email unchanged (same value) → no re-verification.
        verify(emailVerificationService, never()).replaceForNewEmail(any());
    }

    @Test
    void update_savesDocumentRequisites_andTrimsBlanksToNull() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        ProfileUpdateRequest req = new ProfileUpdateRequest(
                "Іван Новий", "+380671112233", "Нова Компанія",
                Set.of(Trade.TILING), "ivan@example.com",
                "ФОП Іваненко Іван", "  1234567890  ", "Київ, вул. Хрещатик 1",
                "UA123456789012345678901234567", "ПриватБанк",
                true, "123456789012", (short) 3, new java.math.BigDecimal("5.00"),
                "  ", com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);

        UserResponse resp = profileService.updateProfile(userId, req);

        assertThat(u.getLegalName()).isEqualTo("ФОП Іваненко Іван");
        assertThat(u.getTaxId()).isEqualTo("1234567890");      // trimmed
        assertThat(u.getIban()).isEqualTo("UA123456789012345678901234567");
        assertThat(u.isVatPayer()).isTrue();
        assertThat(u.getVatId()).isEqualTo("123456789012");
        assertThat(u.getTaxGroup()).isEqualTo((short) 3);
        assertThat(u.getTaxRate()).isEqualByComparingTo("5.00");
        assertThat(u.getDocCity()).isNull();                   // blank → null
        assertThat(u.getActNumberFormat()).isEqualTo(com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);
        assertThat(resp.legalName()).isEqualTo("ФОП Іваненко Іван");
        assertThat(resp.actNumberFormat()).isEqualTo(com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);
    }

    @Test
    void update_nullVatPayerAndFormat_leaveExistingValuesUntouched() {
        // An older client that doesn't send the flags must not reset them.
        User u = user(true, "ivan@example.com");
        u.setVatPayer(true);
        u.setActNumberFormat(com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, req("ivan@example.com")); // both flags null

        assertThat(u.isVatPayer()).isTrue();
        assertThat(u.getActNumberFormat()).isEqualTo(com.majstr.backend.entity.ActNumberFormat.WITH_YEAR);
    }

    @Test
    void update_unverifiedEmailChanged_setsEmailAndReissuesVerification() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(emailPolicyService.canonicalize("new@example.com")).willReturn("new@example.com");
        given(userRepository.existsByEmailIgnoreCase("new@example.com")).willReturn(false);
        given(userRepository.existsByEmailCanonical("new@example.com")).willReturn(false);

        profileService.updateProfile(userId, req("New@Example.com"));

        assertThat(u.getEmail()).isEqualTo("new@example.com"); // normalized
        assertThat(u.getEmailCanonical()).isEqualTo("new@example.com");
        assertThat(u.isEmailVerified()).isFalse();
        verify(emailVerificationService).replaceForNewEmail(u);
    }

    @Test
    void update_disposableEmailDomain_throwsAndKeepsOldEmail() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        org.mockito.BDDMockito.willThrow(
                        new com.majstr.backend.exception.EmailDomainNotAllowedException("error.email.domain-not-allowed"))
                .given(emailPolicyService).assertAcceptable("throwaway@mailinator.com");

        assertThatThrownBy(() -> profileService.updateProfile(userId, req("throwaway@mailinator.com")))
                .isInstanceOf(com.majstr.backend.exception.EmailDomainNotAllowedException.class);
        assertThat(u.getEmail()).isEqualTo("old@example.com");
        verify(emailVerificationService, never()).replaceForNewEmail(any());
    }

    @Test
    void update_unverifiedEmailTaken_throwsConflictAndKeepsOldEmail() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(userRepository.existsByEmailIgnoreCase("taken@example.com")).willReturn(true);

        assertThatThrownBy(() -> profileService.updateProfile(userId, req("taken@example.com")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        assertThat(u.getEmail()).isEqualTo("old@example.com");
        verify(emailVerificationService, never()).replaceForNewEmail(any());
    }

    @Test
    void update_verifiedEmail_changeIgnoredButRestSaved() {
        User u = user(true, "verified@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, req("hacker@example.com"));

        assertThat(u.getEmail()).isEqualTo("verified@example.com"); // locked, ignored
        assertThat(u.getFullName()).isEqualTo("Іван Новий");        // rest still saved
        verify(emailVerificationService, never()).replaceForNewEmail(any());
        // Uniqueness is never even checked for a locked email.
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
    }

    @Test
    void recordPrivacyConsent_stampsWhenNull() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.recordPrivacyConsent(userId);

        assertThat(u.getConsentedToPrivacyAt()).isNotNull();
        assertThat(resp.consentedToPrivacyAt()).isNotNull();
    }

    @Test
    void recordPrivacyConsent_idempotentWhenAlreadySet() {
        User u = user(true, "ivan@example.com");
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        u.setConsentedToPrivacyAt(earlier);
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.recordPrivacyConsent(userId);

        assertThat(u.getConsentedToPrivacyAt()).isEqualTo(earlier); // not overwritten
    }

    @Test
    void acknowledgeClientData_stampsWhenNull() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.acknowledgeClientData(userId);

        assertThat(u.getAcknowledgedClientDataAt()).isNotNull();
        assertThat(resp.acknowledgedClientDataAt()).isNotNull();
    }

    @Test
    void uploadLogo_rejectsAnOversizedFile_andStoresNothing() throws Exception {
        // The old code only checked `length < 4` and trusted a stale comment claiming Spring
        // capped multipart at 2 MB. It never did — and the global cap has since grown to
        // 15 MB for photo imports, so a 15 MB "logo" was accepted, stored, and then read
        // whole into EVERY rendered PDF.
        UUID userId = UUID.randomUUID();
        given(userRepository.findById(userId))
                .willReturn(Optional.of(User.builder().id(userId).build())); // loaded before the check
        byte[] huge = new byte[3 * 1024 * 1024];
        huge[0] = (byte) 0x89; huge[1] = 'P'; huge[2] = 'N'; huge[3] = 'G'; // a valid PNG header
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "logo.png", "image/png", huge);

        assertThatThrownBy(() -> profileService.uploadLogo(userId, file))
                .isInstanceOf(com.majstr.backend.storage.UnsupportedMediaTypeException.class);

        verify(storage, never()).store(any(), anyLong(), any(), any(), any());
    }

    // ---- custom trades (user_trade) ---------------------------------------

    @Test
    void addCustomTrade_savesTrimmedNameAtTheNextSortSlot() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(userTradeRepository.existsByUserIdAndNameIgnoreCase(userId, "Натяжні стелі")).willReturn(false);
        given(userTradeRepository.nextSortOrder(userId)).willReturn(2);

        profileService.addCustomTrade(userId, "  Натяжні стелі  ");

        var captor = org.mockito.ArgumentCaptor.forClass(UserTrade.class);
        verify(userTradeRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Натяжні стелі");
        assertThat(captor.getValue().getSortOrder()).isEqualTo(2);
    }

    @Test
    void addCustomTrade_duplicateName_throwsConflictAndNeverSaves() {
        given(userRepository.findById(userId)).willReturn(Optional.of(user(true, "ivan@example.com")));
        given(userTradeRepository.existsByUserIdAndNameIgnoreCase(userId, "Натяжні стелі")).willReturn(true);

        assertThatThrownBy(() -> profileService.addCustomTrade(userId, "Натяжні стелі"))
                .isInstanceOf(CustomTradeDuplicateException.class);
        verify(userTradeRepository, never()).save(any());
    }

    @Test
    void renameCustomTrade_deniedForATradeOwnedByAnotherUser() {
        UUID tradeId = UUID.randomUUID();
        given(userTradeRepository.findByIdAndUserId(tradeId, userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.renameCustomTrade(userId, tradeId, "Кондиціонери"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteCustomTrade_alreadyGoneIsANoOp() {
        UUID tradeId = UUID.randomUUID();
        given(userRepository.findById(userId)).willReturn(Optional.of(user(true, "ivan@example.com")));
        given(userTradeRepository.findByIdAndUserId(tradeId, userId)).willReturn(Optional.empty());

        // Idempotent — a replayed/duplicate delete of an already-gone custom trade is fine.
        profileService.deleteCustomTrade(userId, tradeId);

        verify(userTradeRepository, never()).delete(any());
    }
}
