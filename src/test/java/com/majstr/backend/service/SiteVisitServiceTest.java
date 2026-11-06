package com.majstr.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

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
    void aClickLabelIsLowerCasedAndKeptOnlyInOurOwnAlphabet() {
        assertThat(SiteVisitService.target(" Hero:Register ")).isEqualTo("hero:register");
        assertThat(SiteVisitService.target("link:/kalkulyator-plytky")).isEqualTo("link:/kalkulyator-plytky");
        assertThat(SiteVisitService.target("anchor:#faq")).isEqualTo("anchor:#faq");
        assertThat(SiteVisitService.target("Почати безкоштовно")).isNull();
        assertThat(SiteVisitService.target("x".repeat(61))).isNull();
        assertThat(SiteVisitService.target(null)).isNull();
    }

    @Test
    void theLanguageKeepsOnlyItsPrimaryTag() {
        assertThat(SiteVisitService.language("uk-UA")).isEqualTo("uk");
        assertThat(SiteVisitService.language("PL")).isEqualTo("pl");
        assertThat(SiteVisitService.language("zh-Hant-TW")).isEqualTo("zh");
        assertThat(SiteVisitService.language("not a language")).isNull();
    }

    @Test
    void aSourceWithoutUtm_countsEvenWhenTheOtherSideHasNoRows() {
        Map<String, Long> visitors = new HashMap<>();
        visitors.put(null, 3L);
        visitors.put("google", 2L);

        assertThat(SiteVisitService.channels(visitors, null))
                .extracting(c -> c.source() + ":" + c.visitors() + "/" + c.registered())
                .containsExactly("null:3/0", "google:2/0");
        assertThat(SiteVisitService.channels(null, visitors))
                .extracting(c -> c.source() + ":" + c.visitors() + "/" + c.registered())
                .containsExactly("null:0/3", "google:0/2");
    }
}
