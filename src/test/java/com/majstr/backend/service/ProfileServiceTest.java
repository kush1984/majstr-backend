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
    @Mock com.majstr.backend.service.AbuseLimits abuseLimits;
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Mock CatalogTemplateService catalogTemplateService;
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

    /** V190: a trade he did not have brings its starter catalog — only the NEW ones, never asked. */
    @Test
    void update_addedTradesFillTheirCatalog_keptOnesAreNotRefilled() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, new ProfileUpdateRequest("Іван", null, null,
                Set.of(Trade.ELECTRICAL, Trade.TILING), "ivan@example.com",
                null, null, null, null, null, null, null, null, null, null, null));

        verify(catalogTemplateService).addTemplatesForTrades(u, Set.of(Trade.TILING));
    }

    @Test
    void update_removingATradeFillsNothing() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, new ProfileUpdateRequest("Іван", null, null,
                Set.of(), "ivan@example.com",
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(u.getTrades()).isEmpty();
        verify(catalogTemplateService, never()).addTemplatesForTrades(any(), any());
    }

    /** V190: name, phone and company are optional in the profile too — blank lands as NULL — while
     *  no name was ever given. */
    @Test
    void update_blankNamePhoneAndCompany_areStoredAsNull() {
        User u = user(true, "ivan@example.com");
        u.setFullName(null);
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateProfile(userId, new ProfileUpdateRequest(" ", null, "",
                Set.of(Trade.ELECTRICAL), "ivan@example.com",
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(u.getFullName()).isNull();
        assertThat(u.getPhone()).isNull();
        assertThat(u.getCompanyName()).isNull();
    }

    /** Review: once given, the name may change but not be erased — documents may already carry it. */
    @Test
    void update_erasingAGivenName_isRefused_andNothingIsSaved() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        assertThatThrownBy(() -> profileService.updateProfile(userId, new ProfileUpdateRequest("  ", null, null,
                Set.of(Trade.ELECTRICAL), "ivan@example.com",
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.majstr.backend.exception.ProfileNameRequiredException.class);
        assertThat(u.getFullName()).isEqualTo("Старе Ім'я");
    }

    /** V193: setting trades on any door answers «Чим займаєтесь?» — the first answer is kept. */
    @Test
    void settingTrades_stampsTheAnswerOnce() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateTrades(userId, new ProfileTradesRequest(Set.of()));
        Instant first = u.getTradesOnboardedAt();
        profileService.updateTrades(userId, new ProfileTradesRequest(Set.of(Trade.TILING)));

        assertThat(first).isNotNull();
        assertThat(u.getTradesOnboardedAt()).isEqualTo(first);
    }

    @Test
    void updateTrades_replacesTheSet_andFillsTheCatalogOfTheNewOnes() {
        User u = user(true, "ivan@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.updateTrades(userId,
                new ProfileTradesRequest(Set.of(Trade.PAINTER, Trade.FLOORING)));

        assertThat(u.getTrades()).containsExactlyInAnyOrder(Trade.PAINTER, Trade.FLOORING);
        assertThat(resp.trades()).containsExactlyInAnyOrder(Trade.PAINTER, Trade.FLOORING);
        verify(catalogTemplateService).addTemplatesForTrades(u, Set.of(Trade.PAINTER, Trade.FLOORING));
    }

    @Test
    void updateTrades_anEmptySet_isASkip_andFillsNothing() {
        User u = user(true, "ivan@example.com");
        u.setTrades(new LinkedHashSet<>());
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        profileService.updateTrades(userId, new ProfileTradesRequest(Set.of()));

        assertThat(u.getTrades()).isEmpty();
        verify(catalogTemplateService, never()).addTemplatesForTrades(any(), any());
    }

    /** The sheet writes the three fields a document prints, and leaves everything else alone. */
    @Test
    void updateSender_writesNamePhoneCompany_andTouchesNothingElse() {
        User u = user(true, "ivan@example.com");
        u.setLegalName("ФОП Старий");
        u.setIban("UA00");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse resp = profileService.updateSender(userId,
                new SenderProfileRequest("  Іван Петренко ", " ", null));

        assertThat(u.getFullName()).isEqualTo("Іван Петренко");
        assertThat(u.getPhone()).isNull();
        assertThat(u.getCompanyName()).isNull();
        assertThat(u.getLegalName()).isEqualTo("ФОП Старий");
        assertThat(u.getIban()).isEqualTo("UA00");
        assertThat(u.getTrades()).containsExactly(Trade.ELECTRICAL);
        assertThat(resp.fullName()).isEqualTo("Іван Петренко");
        verify(catalogTemplateService, never()).addTemplatesForTrades(any(), any());
    }

    /** V191/V193: the call number is kept APART from the documents' phone; the first ask is kept, and
     *  asking again reopens a call the admin had marked done. */
    @Test
    void requestHelp_keepsTheNumberApart_andStampsOnlyTheFirstAsk() {
        User u = user(true, "ivan@example.com");
        u.setPhone(null);
        given(userRepository.findById(userId)).willReturn(Optional.of(u));

        UserResponse first = profileService.requestHelp(userId, new HelpRequest(" +380501112233 "));
        Instant stamped = u.getHelpRequestedAt();
        u.setHelpHandledAt(Instant.now());
        profileService.requestHelp(userId, new HelpRequest("+380670000000"));

        assertThat(first.phone()).as("never printed on a document").isNull();
        assertThat(first.helpRequestedAt()).isNotNull();
        assertThat(u.getHelpRequestedAt()).isEqualTo(stamped);
        assertThat(u.getHelpPhone()).isEqualTo("+380670000000");
        assertThat(u.getHelpHandledAt()).isNull();
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
                        new com.majstr.backend.exception.EmailDomainNotAllowedException(
                                com.majstr.backend.exception.EmailDomainNotAllowedException.Reason.DISPOSABLE))
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
        byte[] huge = new byte[3 * 1024 * 1024];
        huge[0] = (byte) 0x89; huge[1] = 'P'; huge[2] = 'N'; huge[3] = 'G'; // a valid PNG header
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "logo.png", "image/png", huge);

        assertThatThrownBy(() -> profileService.uploadLogo(userId, file))
                .isInstanceOf(com.majstr.backend.storage.UnsupportedMediaTypeException.class);

        verify(storage, never()).store(any(), anyLong(), any(), any(), any());
    }

    @Test
    void changingAnEmail_checksTheAddressAndTheLimitBeforeTheTransaction_andLocksTheCanonicalForm() {
        User u = user(false, "old@example.com");
        given(userRepository.findById(userId)).willReturn(Optional.of(u));
        given(emailPolicyService.canonicalize("new@example.com")).willReturn("new@example.com");

        profileService.updateProfile(userId, req("New@Example.com"));

        var order = org.mockito.Mockito.inOrder(emailPolicyService, abuseLimits, transactionManager, userRepository);
        order.verify(emailPolicyService).assertAcceptable("new@example.com");
        order.verify(abuseLimits).requireEmailChange(userId);
        order.verify(transactionManager).getTransaction(any());
        order.verify(userRepository).lockCanonicalEmail("new@example.com");
    }

    @Test
    void savingAProfileWithTheSameEmail_spendsNoLimitAndNoLookup() {
        given(userRepository.findById(userId)).willReturn(Optional.of(user(false, "same@example.com")));

        profileService.updateProfile(userId, req("same@example.com"));

        verify(abuseLimits, never()).requireEmailChange(any());
        verify(emailPolicyService, never()).assertAcceptable(any());
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};

    private static org.springframework.mock.web.MockMultipartFile logoFile() {
        return new org.springframework.mock.web.MockMultipartFile("file", "logo.jpg", "image/jpeg", JPEG);
    }

    @Test
    void uploadLogo_storesBeforeTheTransaction_andDeletesTheOldFileAfterIt() throws Exception {
        User user = User.builder().id(userId).logoUrl("logos/old.jpg").build();
        given(userRepository.existsById(userId)).willReturn(true);
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(storage.store(any(), anyLong(), any(), any(), any()))
                .willReturn(new com.majstr.backend.storage.StoredObject("logos/new.jpg", 8, "image/jpeg"));

        assertThat(profileService.uploadLogo(userId, logoFile())).isEqualTo("logos/new.jpg");

        assertThat(user.getLogoUrl()).isEqualTo("logos/new.jpg");
        var order = org.mockito.Mockito.inOrder(storage, transactionManager);
        order.verify(storage).store(any(), anyLong(), any(), any(), any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
        order.verify(storage).delete("logos/old.jpg");
    }

    @Test
    void uploadLogo_removesTheNewFile_whenTheRowCannotBeUpdated() throws Exception {
        given(userRepository.existsById(userId)).willReturn(true);
        given(userRepository.findById(userId)).willReturn(Optional.empty());
        given(storage.store(any(), anyLong(), any(), any(), any()))
                .willReturn(new com.majstr.backend.storage.StoredObject("logos/new.jpg", 8, "image/jpeg"));

        assertThatThrownBy(() -> profileService.uploadLogo(userId, logoFile()))
                .isInstanceOf(com.majstr.backend.exception.ResourceNotFoundException.class);

        verify(storage).delete("logos/new.jpg");
    }

    @Test
    void deleteLogo_commitsTheRowBeforeDeletingTheFile() throws Exception {
        User user = User.builder().id(userId).logoUrl("logos/old.jpg").build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        profileService.deleteLogo(userId);

        assertThat(user.getLogoUrl()).isNull();
        var order = org.mockito.Mockito.inOrder(transactionManager, storage);
        order.verify(transactionManager).commit(any());
        order.verify(storage).delete("logos/old.jpg");
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
