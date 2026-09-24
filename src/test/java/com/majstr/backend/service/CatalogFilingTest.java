package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides which trade an estimate line belongs to when the catalog row that priced it
 * is filed under another one — see {@link CatalogFiling}.
 */
class CatalogFilingTest {

    private static final String NAME = "Шпаклювання фінішне (2–4 рази)";

    /** The master's own row, stored under whichever trade claimed the name first (V118). */
    private static CatalogItem storedUnder(Trade trade, String category) {
        return CatalogItem.builder()
                .name(NAME).type(ItemType.WORK).unit(Unit.M2).trade(trade).category(category)
                .build();
    }

    private static Map<String, String> library(String category) {
        Map<String, String> folders = new HashMap<>();
        folders.put(CatalogFiling.key(NAME, ItemType.WORK, Unit.M2), category);
        return folders;
    }

    @Test
    void aPositionTheWorkingTradeAlsoShipsIsFiledUnderThatTrade() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                Trade.PAINTER, library("Шпаклювання та шліфування"),
                NAME, ItemType.WORK, Unit.M2,
                storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.PAINTER);
        assertThat(filing.category()).isEqualTo("Шпаклювання та шліфування");
    }

    /** No guessing: a name the working trade does not ship keeps the filing it already had. */
    @Test
    void aPositionTheWorkingTradeDoesNotShipKeepsItsStoredFiling() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                Trade.PAINTER, Map.of(), NAME, ItemType.WORK, Unit.M2,
                storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.DRYWALL);
        assertThat(filing.category()).isEqualTo("Оздоблення під фарбування");
    }

    /** The unit is part of the identity: an m² position and its м.п. twin are different work. */
    @Test
    void aDifferentUnitIsADifferentPositionAndIsNotRefiled() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                Trade.PAINTER, library("Шпаклювання та шліфування"),
                NAME, ItemType.WORK, Unit.LINEAR_METER,
                storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.DRYWALL);
    }

    @Test
    void noWorkingTradeChangesNothing() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                null, library("Шпаклювання та шліфування"), NAME, ItemType.WORK, Unit.M2,
                storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.DRYWALL);
        assertThat(filing.category()).isEqualTo("Оздоблення під фарбування");
    }

    /**
     * A bundle line whose name this master does not stock applies at 0 ₴ — that is about the PRICE.
     * Where it belongs is a separate question, and the library can still answer it.
     */
    @Test
    void aLineThatMatchedNoCatalogRowStillLandsInTheRightFolder() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                Trade.PAINTER, library("Шпаклювання та шліфування"),
                NAME, ItemType.WORK, Unit.M2, null);

        assertThat(filing.trade()).isEqualTo(Trade.PAINTER);
        assertThat(filing.category()).isEqualTo("Шпаклювання та шліфування");
    }

    /**
     * A library row with no folder of its own files the position «nowhere in particular» — the
     * stored folder is kept rather than «Без категорії» invented, which is the rule the PWA's
     * catalog tree already follows when it shows a shared position under a second trade.
     */
    @Test
    void aLibraryRowWithNoFolderKeepsTheStoredOne() {
        Map<String, String> folders = new HashMap<>();
        folders.put(CatalogFiling.key(NAME, ItemType.WORK, Unit.M2), null);

        CatalogFiling.Filing filing = CatalogFiling.fileUnder(Trade.PAINTER, folders,
                NAME, ItemType.WORK, Unit.M2, storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.PAINTER);
        assertThat(filing.category()).isEqualTo("Оздоблення під фарбування");
    }

    /** The key is {@link NameKeys}', so untidy spelling still joins — «( плюс» and double spaces. */
    @Test
    void theKeySurvivesTheUntidinessRealSeedDataHas() {
        assertThat(CatalogFiling.key("Укладання  плитки ( по діагоналі )", ItemType.WORK, Unit.M2))
                .isEqualTo(CatalogFiling.key("укладання плитки (по діагоналі)", ItemType.WORK, Unit.M2));
    }
}
