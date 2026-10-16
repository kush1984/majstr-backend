package com.majstr.backend.integration;

import com.majstr.backend.service.ReferralService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** V172 — a ?ref=youtube link stamps its own source, so the admin's by-source funnel can count it. */
class YoutubeReferralIntegrationTest extends IntegrationTestBase {

    @Autowired ReferralService referralService;

    @Test
    void theYoutubeLinkIsItsOwnSource_inAnyCase_asALinkOrAPromoCode() {
        assertThat(referralService.resolve("youtube", null).source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve("YouTube", null).source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve(null, "youtube").source()).isEqualTo("YOUTUBE");
        assertThat(referralService.resolve("youtube", null).referredByUserId()).isNull();
    }

    @Test
    void theOtherSourcesAreUntouched() {
        assertThat(referralService.resolve("liga", null).source()).isEqualTo("LIGA");
        assertThat(referralService.resolve("tiktok", null).source()).isEqualTo(ReferralService.DIRECT);
        assertThat(referralService.resolve(null, null).source()).isEqualTo(ReferralService.DIRECT);
    }
}
