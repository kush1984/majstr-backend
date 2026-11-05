package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The small rules of the visit report that need no database. The rest is {@code SiteVisitIntegrationTest}. */
class SiteVisitServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 7);

    @Test
    void registrationsStartWhenTheCounterStarted_notBeforeIt() {
        assertThat(SiteVisitService.registrationsFrom(FROM, LocalDate.of(2026, 11, 5)))
                .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void anOlderCounterLeavesThePeriodAlone() {
        assertThat(SiteVisitService.registrationsFrom(FROM, LocalDate.of(2026, 9, 1))).isEqualTo(FROM);
        assertThat(SiteVisitService.registrationsFrom(FROM, null)).isEqualTo(FROM);
    }

    @Test
    void kyivIsOneTimeZoneWhateverTheBrowserCallsIt() {
        assertThat(SiteVisitService.timeZone("Europe/Kiev")).isEqualTo("Europe/Kyiv");
        assertThat(SiteVisitService.timeZone("Europe/Zaporozhye")).isEqualTo("Europe/Kyiv");
        assertThat(SiteVisitService.timeZone(" Europe/Kyiv ")).isEqualTo("Europe/Kyiv");
        assertThat(SiteVisitService.timeZone("Europe/Warsaw")).isEqualTo("Europe/Warsaw");
        assertThat(SiteVisitService.timeZone("America/Argentina/Buenos_Aires")).isEqualTo("America/Argentina/Buenos_Aires");
    }

    @Test
    void anythingElseInTheTimeZoneFieldIsDropped() {
        assertThat(SiteVisitService.timeZone("<script>")).isNull();
        assertThat(SiteVisitService.timeZone("")).isNull();
        assertThat(SiteVisitService.timeZone(null)).isNull();
    }

    @Test
    void theLanguageKeepsOnlyItsPrimaryTag() {
        assertThat(SiteVisitService.language("uk-UA")).isEqualTo("uk");
        assertThat(SiteVisitService.language("PL")).isEqualTo("pl");
        assertThat(SiteVisitService.language("zh-Hant-TW")).isEqualTo("zh");
        assertThat(SiteVisitService.language("not a language")).isNull();
    }
}
