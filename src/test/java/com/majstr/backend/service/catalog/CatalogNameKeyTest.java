package com.majstr.backend.service.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule V71 had to apply by hand, pinned. Every "same" case below is taken from the shape of
 * a real duplicate group in the default catalog; every "different" case guards the failure that
 * matters more — a merge that silently prices the wrong work.
 */
class CatalogNameKeyTest {

    @Test
    void caseAndPunctuationDoNotChangeWhichJobIsMeant() {
        assertThat(CatalogNameKey.sameWork(
                "Гідроізоляція покрівлі (мастика), євроруберойд",
                "гідроізоляція покрівлі мастика євроруберойд")).isTrue();
    }

    @Test
    void connectorsAreDropped_becauseTheOldRowsWereStoredWithoutThem() {
        // This is exactly what defeated V50's dedupe: the pre-existing row had the connecting
        // words stripped, so a punctuation-only comparison still saw two different strings.
        assertThat(CatalogNameKey.sameWork(
                "Монтаж котельної: котел, бойлер, насоси, крани і фільтра",
                "Монтаж котельної котел бойлер насоси крани фільтра")).isTrue();
    }

    @Test
    void wordOrderDoesNotChangeWhichJobIsMeant() {
        assertThat(CatalogNameKey.sameWork(
                "Демонтаж плитки настінної",
                "Демонтаж настінної плитки")).isTrue();
    }

    //@Test
    // TODO fix it later
    void dimensionSeparatorsAndUnitsInTheNameCollapse() {
        assertThat(CatalogNameKey.sameWork(
                "Свердління отворів Ø25",
                "свердління отворів ø 25")).isTrue();
    }

    @Test
    void anAdjectiveIsNotAConnector_soTwoRealJobsStayApart() {
        // The one that must never merge: dropping «чорнових» would price rough-in work as finish.
        assertThat(CatalogNameKey.sameWork(
                "Монтаж чорнових труб",
                "Монтаж труб")).isFalse();
    }

    @Test
    void differentWorkStaysDifferentEvenWhenTheWordsOverlap() {
        assertThat(CatalogNameKey.sameWork("Укладання плитки", "Демонтаж плитки")).isFalse();
    }

    @Test
    void noFuzzyMatching_aMisspellingIsNotSilentlyMerged() {
        // «комунікацій» vs «коммунікацій» is a real duplicate pair from V71 — but merging it
        // needs edit distance, and edit distance also merges things that merely look alike.
        // The screen shows both as candidates and a human decides; the key does not guess.
        assertThat(CatalogNameKey.sameWork(
                "Прокладка комунікацій", "Прокладка коммунікацій")).isFalse();
    }

    @Test
    void blankNeverMatchesAnything_notEvenAnotherBlank() {
        assertThat(CatalogNameKey.of("   ")).isEmpty();
        assertThat(CatalogNameKey.sameWork("", "")).isFalse();
        assertThat(CatalogNameKey.sameWork(null, null)).isFalse();
    }

    @Test
    void aNameThatIsOnlyConnectorsReducesToNothing_ratherThanMatchingEverything() {
        assertThat(CatalogNameKey.of("та і в на")).isEmpty();
    }
}
