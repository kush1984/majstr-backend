package com.majstr.backend.service;

import com.majstr.backend.entity.CatalogItem;
import com.majstr.backend.entity.CatalogItemSource;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide which trade an estimate line belongs to when it arrives by NAME rather than
 * by catalog row — see {@link CatalogFiling}.
 */
class CatalogFilingTest {

    private static final String NAME = "Шпаклювання фінішне (2–4 рази)";

    /** One of the master's rows of this name, filed under {@code trade}. */
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

    /** A library row with no folder of its own files the position under the trade, in no folder:
     *  another trade's folder is never borrowed. */
    @Test
    void aLibraryRowWithNoFolderFilesItInNoFolder() {
        Map<String, String> folders = new HashMap<>();
        folders.put(CatalogFiling.key(NAME, ItemType.WORK, Unit.M2), null);

        CatalogFiling.Filing filing = CatalogFiling.fileUnder(Trade.PAINTER, folders,
                NAME, ItemType.WORK, Unit.M2, storedUnder(Trade.DRYWALL, "Оздоблення під фарбування"));

        assertThat(filing.trade()).isEqualTo(Trade.PAINTER);
        assertThat(filing.category()).isNull();
    }

    /** V220: his own row of the working trade is the answer — his folder, not the library's. */
    @Test
    void hisOwnRowOfTheWorkingTradeKeepsHisFolder() {
        CatalogFiling.Filing filing = CatalogFiling.fileUnder(
                Trade.PAINTER, library("Шпаклювання та шліфування"),
                NAME, ItemType.WORK, Unit.M2, storedUnder(Trade.PAINTER, "Моя папка"));

        assertThat(filing).isEqualTo(new CatalogFiling.Filing(Trade.PAINTER, "Моя папка"));
    }

    @Test
    void rowFor_prefersTheWorkingTradesRow_thenAVisibleOne() {
        CatalogItem drywall = row(Trade.DRYWALL, "100.00", CatalogItemSource.LIBRARY);
        CatalogItem painter = row(Trade.PAINTER, "150.00", CatalogItemSource.LIBRARY);

        assertThat(CatalogFiling.rowFor(List.of(drywall, painter), Trade.PAINTER, Set.of())).isSameAs(painter);
        // No row of the working trade: a row of a trade on his profile, not the hidden one.
        assertThat(CatalogFiling.rowFor(List.of(drywall, painter), Trade.TILING, Set.of(Trade.PAINTER)))
                .isSameAs(painter);
        assertThat(CatalogFiling.rowFor(List.of(), Trade.PAINTER, Set.of())).isNull();
    }

    /** Owner ruling 2026-11-23: one trade → it; several → the estimate's, when it is one of them. */
    @Test
    void typedLineTrade_neverPicksBetweenTwoTradesOnAHunch() {
        assertThat(CatalogFiling.typedLineTrade(Set.of(Trade.ELECTRICAL), null)).isEqualTo(Trade.ELECTRICAL);
        assertThat(CatalogFiling.typedLineTrade(Set.of(Trade.ELECTRICAL, Trade.TILING), Trade.TILING))
                .isEqualTo(Trade.TILING);
        assertThat(CatalogFiling.typedLineTrade(Set.of(Trade.ELECTRICAL, Trade.TILING), Trade.PAINTER)).isNull();
        assertThat(CatalogFiling.typedLineTrade(Set.of(Trade.ELECTRICAL, Trade.TILING), null)).isNull();
        assertThat(CatalogFiling.typedLineTrade(Set.of(), Trade.TILING)).isNull();
    }

    @Test
    void estimateTrade_isTheOneTradeEveryFiledLineShares() {
        EstimateItem electrical = EstimateItem.builder().trade(Trade.ELECTRICAL).build();
        EstimateItem typed = EstimateItem.builder().build();
        EstimateItem tiling = EstimateItem.builder().trade(Trade.TILING).build();

        assertThat(CatalogFiling.estimateTrade(List.of(electrical, typed))).isEqualTo(Trade.ELECTRICAL);
        assertThat(CatalogFiling.estimateTrade(List.of(electrical, tiling))).isNull();
        assertThat(CatalogFiling.estimateTrade(List.of(typed))).isNull();
    }

    @Test
    void fileTradesByKey_readsTheVisibleRowsFirst() {
        CatalogItem hiddenTiling = row(Trade.TILING, "100.00", CatalogItemSource.LIBRARY);
        CatalogItem electrical = row(Trade.ELECTRICAL, "100.00", CatalogItemSource.LIBRARY);

        assertThat(CatalogFiling.fileTradesByKey(List.of(hiddenTiling, electrical), Set.of(Trade.ELECTRICAL)))
                .containsEntry(CatalogFiling.key(NAME, ItemType.WORK, Unit.M2), Set.of(Trade.ELECTRICAL));
        // Nothing visible of that name: every row still says where he files it.
        assertThat(CatalogFiling.fileTradesByKey(List.of(hiddenTiling), Set.of(Trade.ELECTRICAL)))
                .containsEntry(CatalogFiling.key(NAME, ItemType.WORK, Unit.M2), Set.of(Trade.TILING));
    }

    @Test
    void forMatching_oneRowPerName_theContextsOrNoneAtTwoPrices() {
        CatalogItem electrical = row(Trade.ELECTRICAL, "100.00", CatalogItemSource.LIBRARY);
        CatalogItem tiling = row(Trade.TILING, "150.00", CatalogItemSource.LIBRARY);
        CatalogItem tilingSamePrice = row(Trade.TILING, "100.00", CatalogItemSource.LIBRARY);
        Set<Trade> both = Set.of(Trade.ELECTRICAL, Trade.TILING);

        assertThat(CatalogFiling.forMatching(List.of(electrical, tiling), both, Trade.TILING))
                .containsExactly(tiling);
        // No context and two prices: both stay, and CatalogMatcher refuses the tie.
        assertThat(CatalogFiling.forMatching(List.of(electrical, tiling), both, null))
                .containsExactly(electrical, tiling);
        // One price: one answer.
        assertThat(CatalogFiling.forMatching(List.of(electrical, tilingSamePrice), both, null))
                .containsExactly(electrical);
        // A trade off his profile is not in the catalog he matches against.
        assertThat(CatalogFiling.forMatching(List.of(electrical, tiling), Set.of(Trade.ELECTRICAL), null))
                .containsExactly(electrical);
    }

    private static CatalogItem row(Trade trade, String price, CatalogItemSource source) {
        return CatalogItem.builder()
                .name(NAME).type(ItemType.WORK).unit(Unit.M2).trade(trade).source(source)
                .defaultPrice(new BigDecimal(price)).build();
    }

    /** The key is {@link NameKeys}', so untidy spelling still joins — «( плюс» and double spaces. */
    @Test
    void theKeySurvivesTheUntidinessRealSeedDataHas() {
        assertThat(CatalogFiling.key("Укладання  плитки ( по діагоналі )", ItemType.WORK, Unit.M2))
                .isEqualTo(CatalogFiling.key("укладання плитки (по діагоналі)", ItemType.WORK, Unit.M2));
    }
}
