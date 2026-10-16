package com.majstr.backend.integration;

import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.service.AuthService;
import com.majstr.backend.service.ReferralService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V172 / V173 — a ?ref=youtube link and the LIGA-MEETUP code each stamp a source of their own, so
 * the admin's by-source funnel can count them apart from DIRECT and from LIGA.
 */
class YoutubeReferralIntegrationTest extends IntegrationTestBase {

    @Autowired ReferralService referralService;
    @Autowired AuthService authService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aRegistrationFromTheYoutubeLinkIsStampedYoutube() {
        String email = "yt" + UUID.randomUUID().toString().substring(0, 8) + "@gmail.com";
        authService.register(new RegisterRequest(email, "Sup3r-Secret!", "Майстер з YouTube",
                Set.of(Trade.TILING), null, "+380501112233", "ФОП", true, "youtube", null, null, null, null));

        assertThat(jdbc.queryForObject("SELECT referral_source FROM users WHERE email = ?", String.class, email))
                .isEqualTo("YOUTUBE");
    }

    @Test
    void theYoutubeLinkIsItsOwnSource_inAnyCase_asALinkOrAPromoCode() {
        assertThat(referralService.resolve("youtube", null).source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve("YouTube", null).source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve(null, "youtube").source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve("youtube", null).referredByUserId()).isNull();
    }

    @Test
    void theMeetupCodeIsApartFromLiga() {
        assertThat(referralService.resolve("liga-meetup", null).source()).isEqualTo("LIGA_MEETUP");
        assertThat(referralService.resolve(null, "LIGA-MEETUP").source()).isEqualTo("LIGA_MEETUP");
        assertThat(referralService.resolve("liga", null).source()).isEqualTo("LIGA");
    }

    @Test
    void theOtherSourcesAreUntouched() {
        assertThat(referralService.resolve("tiktok", null).source()).isEqualTo(ReferralService.DIRECT);
        assertThat(referralService.resolve(null, null).source()).isEqualTo(ReferralService.DIRECT);
    }
}
