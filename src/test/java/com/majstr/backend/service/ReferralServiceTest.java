package com.majstr.backend.service;

import com.majstr.backend.entity.Partner;
import com.majstr.backend.repository.PartnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock PartnerRepository partnerRepository;
    @InjectMocks ReferralService referralService;

    private void partner(String code, String source) {
        given(partnerRepository.findByCodeIgnoreCaseAndActiveTrue(code))
                .willReturn(Optional.of(Partner.builder().code(code).source(source).active(true).build()));
    }

    @Test
    void ref_resolvesToPartnerSource_andWinsOverPromo() {
        partner("liga", "LIGA"); // only the ref is looked up — it short-circuits
        assertThat(referralService.resolveSource("liga", "SOMETHING")).isEqualTo("LIGA");
    }

    @Test
    void promoCode_usedWhenNoRef() {
        partner("liga", "LIGA");
        assertThat(referralService.resolveSource(null, "liga")).isEqualTo("LIGA");
    }

    @Test
    void refIsTrimmedBeforeLookup() {
        partner("liga", "LIGA");
        assertThat(referralService.resolveSource("  liga  ", null)).isEqualTo("LIGA");
    }

    @Test
    void unknownCode_fallsBackToDirect() {
        given(partnerRepository.findByCodeIgnoreCaseAndActiveTrue("xyz")).willReturn(Optional.empty());
        assertThat(referralService.resolveSource("xyz", null)).isEqualTo("DIRECT");
    }

    @Test
    void noRefNoPromo_isDirect_withNoLookup() {
        assertThat(referralService.resolveSource(null, null)).isEqualTo("DIRECT");
        assertThat(referralService.resolveSource("  ", "")).isEqualTo("DIRECT");
        verify(partnerRepository, never()).findByCodeIgnoreCaseAndActiveTrue(any());
    }
}
