package com.majstr.backend.service.importer;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What decides the price on a dictated line. The rule that matters most here is the one that
 * REFUSES: a position we cannot pin to exactly one catalog row comes back unmatched and visibly
 * flagged, because a wrong price looks exactly like a right one on the review screen.
 */
class CatalogMatcherTest {

    private final CatalogItem wallpaper = item("Поклейка шпалер", Unit.M2, "150");
    private final CatalogItem plasterWalls = item("Штукатурка стін по маяках", Unit.M2, "320");
    private final CatalogItem plasterCeiling = item("Штукатурка стелі по маяках", Unit.M2, "380");
    private final List<CatalogItem> catalog = List.of(wallpaper, plasterWalls, plasterCeiling);

    @Test
    void exactWording_matches() {
        assertThat(CatalogMatcher.match("поклейка шпалер", catalog)).contains(wallpaper);
    }

    @Test
    void spokenInflection_matchesTheCatalogWording() {
        // The whole point: nobody dictates his own price list verbatim. Ukrainian endings live past
        // the fifth letter, so the stems «покле»/«шпале» carry the meaning either way round.
        assertThat(CatalogMatcher.match("поклеїти шпалери", catalog)).contains(wallpaper);
        assertThat(CatalogMatcher.match("штукатурити стіни по маяках", catalog)).contains(plasterWalls);
    }

    @Test
    void punctuationAndApostrophes_doNotDecideAnything() {
        List<CatalogItem> withApostrophe = List.of(item("Монтаж в'їзної групи", Unit.PIECE, "900"));
        assertThat(CatalogMatcher.match("монтаж вʼїзної групи", withApostrophe)).isPresent();
    }

    @Test
    void oneSharedWord_isNotAMatch() {
        // «Штукатурка» alone stands between two different jobs at two different prices.
        assertThat(CatalogMatcher.match("штукатурка декоративна венеціанська", catalog)).isEmpty();
    }

    @Test
    void aTieBetweenTwoRows_isRefusedRatherThanGuessed() {
        // The shipping case: finish levels differing by one token. Picking either would put a price
        // the master never chose onto a line he is about to sign.
        List<CatalogItem> levels = List.of(
                item("Шпаклювання стін під фарбування Q3", Unit.M2, "260"),
                item("Шпаклювання стін під фарбування Q4", Unit.M2, "340"));

        assertThat(CatalogMatcher.match("шпаклювання стін під фарбування", levels)).isEmpty();
    }

    @Test
    void digitsAreNeverStemmedAway_soAskingForQ4GetsQ4() {
        List<CatalogItem> levels = List.of(
                item("Шпаклювання стін під фарбування Q3", Unit.M2, "260"),
                item("Шпаклювання стін під фарбування Q4", Unit.M2, "340"));

        assertThat(CatalogMatcher.match("шпаклювання стін під фарбування Q4", levels))
                .map(CatalogItem::getDefaultPrice)
                .contains(new BigDecimal("340"));
    }

    @Test
    void theSameNameUnderTwoUnits_isTheMastersCallNotOurs() {
        List<CatalogItem> both = List.of(
                item("Монтаж плінтуса", Unit.LINEAR_METER, "80"),
                item("Монтаж плінтуса", Unit.PIECE, "120"));

        assertThat(CatalogMatcher.match("монтаж плінтуса", both)).isEmpty();
    }

    @Test
    void nothingToMatchAgainst_isSimplyNoMatch() {
        assertThat(CatalogMatcher.match("поклейка шпалер", List.of())).isEmpty();
        assertThat(CatalogMatcher.match("  ", catalog)).isEmpty();
    }

    @Test
    void aSynonym_winsOverTheDicePass() {
        // «шпалери» alone against these three rows scores exactly the same against «Поклейка шпалер»
        // as the two «Штукатурка …» rows, so the Dice pass refuses it as a tie. A taught synonym
        // must answer anyway — that IS the learning.
        Map<String, UUID> synonyms = Map.of(CatalogMatcher.normalize("шпалери"), wallpaper.getId());

        assertThat(CatalogMatcher.match("шпалери", catalog, synonyms)).contains(wallpaper);
    }

    @Test
    void aSynonymPointingAtADeletedRow_isSilentlyIgnored() {
        // The FK is CASCADE so in practice this cannot survive — but a stale in-memory map during a
        // rebuild must never resurrect a row the master already deleted.
        Map<String, UUID> synonyms = Map.of(
                CatalogMatcher.normalize("щось видалене"), UUID.randomUUID());

        assertThat(CatalogMatcher.match("щось видалене", catalog, synonyms)).isEmpty();
    }

    @Test
    void aSynonym_isNormalizedTheSameWayAtReadTime() {
        // The lookup key is `normalize(spoken)`, and the write path stores `normalize(spoken)` — so
        // «Шпалери!» matches a synonym taught as «шпалери» without a second normalization pass.
        Map<String, UUID> synonyms = Map.of(CatalogMatcher.normalize("шпалери"), wallpaper.getId());

        assertThat(CatalogMatcher.match("Шпалери!", catalog, synonyms)).contains(wallpaper);
    }

    private static CatalogItem item(String name, Unit unit, String price) {
        return CatalogItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .unit(unit)
                .type(ItemType.WORK)
                .defaultPrice(new BigDecimal(price))
                .build();
    }
}
