package com.majstr.backend.service;

import com.majstr.backend.entity.Partner;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.PartnerRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock PartnerRepository partnerRepository;
    @Mock UserRepository userRepository;
    @InjectMocks ReferralService referralService;

    private void partner(String code, String source) {
        given(partnerRepository.findByCodeIgnoreCaseAndActiveTrue(code))
                .willReturn(Optional.of(Partner.builder().code(code).source(source).active(true).build()));
    }

    // ---- partner attribution (unchanged behaviour) ------------------------

    @Test
    void ref_resolvesToPartnerSource_andWinsOverPromo() {
        partner("liga", "LIGA"); // only the ref is looked up — it short-circuits
        assertThat(referralService.resolve("liga", "SOMETHING").source()).isEqualTo("LIGA");
    }

    @Test
    void promoCode_usedWhenNoRef() {
        partner("liga", "LIGA");
        assertThat(referralService.resolve(null, "liga").source()).isEqualTo("LIGA");
    }

    @Test
    void refIsTrimmedBeforeLookup() {
        partner("liga", "LIGA");
        assertThat(referralService.resolve("  liga  ", null).source()).isEqualTo("LIGA");
    }

    @Test
    void unknownCode_fallsBackToDirect() {
        given(partnerRepository.findByCodeIgnoreCaseAndActiveTrue("xyz")).willReturn(Optional.empty());
        var attr = referralService.resolve("xyz", null);
        assertThat(attr.source()).isEqualTo("DIRECT");
        assertThat(attr.referredByUserId()).isNull();
    }

    @Test
    void noRefNoPromo_isDirect_withNoLookup() {
        assertThat(referralService.resolve(null, null).source()).isEqualTo("DIRECT");
        assertThat(referralService.resolve("  ", "").source()).isEqualTo("DIRECT");
        verify(partnerRepository, never()).findByCodeIgnoreCaseAndActiveTrue(any());
        verify(userRepository, never()).findByReferralCode(any());
    }

    // ---- master personal codes (new) --------------------------------------

    @Test
    void masterCode_resolvesToMasterSourceAndReferrerId() {
        UUID referrerId = UUID.randomUUID();
        given(userRepository.findByReferralCode("abc12345"))
                .willReturn(Optional.of(User.builder().id(referrerId).build()));

        var attr = referralService.resolve("m-abc12345", null);

        assertThat(attr.source()).isEqualTo("MASTER");
        assertThat(attr.referredByUserId()).isEqualTo(referrerId);
        verify(partnerRepository, never()).findByCodeIgnoreCaseAndActiveTrue(any());
    }

    @Test
    void masterCode_isCaseInsensitiveOnPrefixAndCode() {
        UUID referrerId = UUID.randomUUID();
        given(userRepository.findByReferralCode("abc12345"))
                .willReturn(Optional.of(User.builder().id(referrerId).build()));

        assertThat(referralService.resolve("M-ABC12345", null).referredByUserId()).isEqualTo(referrerId);
    }

    @Test
    void unknownMasterCode_fallsBackToDirect_notPartner() {
        given(userRepository.findByReferralCode("dead0000")).willReturn(Optional.empty());
        var attr = referralService.resolve("m-dead0000", null);
        assertThat(attr.source()).isEqualTo("DIRECT");
        assertThat(attr.referredByUserId()).isNull();
        verify(partnerRepository, never()).findByCodeIgnoreCaseAndActiveTrue(any());
    }

    // ---- code generation --------------------------------------------------

    @Test
    void generateUniqueCode_retriesOnCollision() {
        given(userRepository.existsByReferralCode(any()))
                .willReturn(true)   // first candidate taken
                .willReturn(false); // second is free
        String code = referralService.generateUniqueCode();
        assertThat(code).isNotBlank();
        verify(userRepository, org.mockito.Mockito.times(2)).existsByReferralCode(any());
    }
}
