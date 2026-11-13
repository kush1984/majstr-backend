package com.majstr.backend.integration;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.EstimateDuplicateRequest;
import com.majstr.backend.dto.EstimateItemRequest;
import com.majstr.backend.dto.EstimateItemResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.MaterialLineRequest;
import com.majstr.backend.dto.PerPositionAnswers;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.dto.TileLayoutRequest;
import com.majstr.backend.dto.TileLayoutView;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.TileLayoutValidationException;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.MaterialCalculatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V203 — a tiling line's layout against the real library norms (iteration-tile-layout, stage 2).
 *
 * <p>«Укладання плитки 300х600» buys CERAMIC_TILE_300X600 at 1,00 per m², packaged by the piece
 * (0,18 m²). With the master's 10 % and no layout, 20 m² is 20 × 1,1 / 0,18 = 122,2 → 123 tiles.</p>
 */
class TileLayoutIntegrationTest extends IntegrationTestBase {

    private static final String TILING_300X600 = "Укладання плитки 300х600";
    private static final BigDecimal TEN = BigDecimal.TEN;

    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialCalculatorService calculatorService;
    @Autowired EstimateService estimateService;

    private UUID ownerId;
    private UUID projectId;
    private UUID estimateId;
    private int sortOrder;

    @BeforeEach
    void seed() {
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Ванна', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
        estimateId = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', 'DRAFT')",
                estimateId, projectId);
        sortOrder = 0;
    }

    @Test
    void withoutALayoutTheTileIsBoughtByArea() {
        addWork(TILING_300X600, Unit.M2, "20");
        CalculatedMaterialLine tile = tileLine(calc());
        assertThat(tile.packages()).isEqualTo(123);
        assertThat(calc().tileLayouts()).isEmpty();
    }

    @Test
    void theLaidPartIsWholeTilesWithNoAllowanceAndTheRestIsBoughtByArea() {
        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        CalculatedMaterialLine adhesiveBefore = line(calc(), "Клей");

        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 300, "9.1", 54, 68, 4));
        MaterialCalculationResponse result = calc();
        CalculatedMaterialLine tile = tileLine(result);

        // 72 laid tiles exactly + 10,9 m² × 1,1 / 0,18 = 66,6 → one sum, rounded up once: 139.
        assertThat(tile.packages()).isEqualTo(139);
        assertThat(tile.sources()).hasSize(2);
        assertThat(tile.sources().get(0).unit()).isEqualTo(Unit.PIECE);
        assertThat(tile.sources().get(0).quantity()).isEqualByComparingTo("72");
        assertThat(tile.sources().get(1).quantity()).isEqualByComparingTo("10.9");
        // The adhesive still follows the whole line.
        assertThat(line(result, "Клей").quantity()).isEqualByComparingTo(adhesiveBefore.quantity());
        assertThat(result.tileLayouts()).singleElement().satisfies(v -> {
            assertThat(v.tilesToBuy()).isEqualTo(72);
            assertThat(v.paused()).isFalse();
            assertThat(v.tileShortMm()).isEqualTo(300);
            assertThat(v.tileLongMm()).isEqualTo(600);
        });
    }

    @Test
    void aWholeLineLaidOutBuysExactlyTheLayoutsTiles() {
        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(300, 600, "21.5", 110, 120, 6));

        CalculatedMaterialLine tile = tileLine(calc());
        assertThat(tile.packages()).isEqualTo(126);
        assertThat(tile.sources()).singleElement()
                .satisfies(s -> assertThat(s.unit()).isEqualTo(Unit.PIECE));
    }

    @Test
    void aLineRenamedToAnotherFormatPausesItsLayout() {
        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 300, "20", 100, 110, 6));
        jdbc.update("UPDATE estimate_items SET name = 'Укладання плитки 600х600' WHERE id = ?", item);

        MaterialCalculationResponse result = calc();
        assertThat(result.tileLayouts()).singleElement()
                .satisfies(v -> assertThat(v.paused()).isTrue());
        assertThat(calculatorService.tileLayouts(estimateId, ownerId)).singleElement()
                .satisfies(v -> assertThat(v.paused()).isTrue());
        assertThat(result.materials()).flatExtracting(CalculatedMaterialLine::sources)
                .noneMatch(s -> s.unit() == Unit.PIECE && item.equals(s.estimateItemId()));
    }

    @Test
    void aFormatInCentimetresIsTheSameTile() {
        UUID item = addWork("Укладання плитки 30х60 у ванній", Unit.M2, "20");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 300, "20", 100, 110, 6));
        assertThat(calc().tileLayouts()).singleElement().satisfies(v -> assertThat(v.paused()).isFalse());
    }

    @Test
    void aCustomFormatIsItsOwnLineAndReachesTheShoppingListByName() {
        UUID item = addWork("Облицювання фартуха плиткою", Unit.M2, "4");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(500, 300, "4", 25, 30, 2));

        MaterialCalculationResponse result = calc();
        CalculatedMaterialLine tile = result.materials().stream()
                .filter(m -> m.name().equals("Плитка 300×500 мм")).findFirst().orElseThrow();
        assertThat(tile.packages()).isEqualTo(32);
        assertThat(tile.packageSize()).isEqualByComparingTo("0.15");
        assertThat(result.coverage().trades()).contains("TILING");

        ShoppingListResponse list = calculatorService.toShoppingList(estimateId, ownerId,
                new MaterialApplyRequest(List.of(new MaterialLineRequest(tile.materialId(), tile.quantity()))));
        ShoppingListItemResponse row = list.items().stream()
                .filter(i -> i.name().equals("Плитка 300×500 мм")).findFirst().orElseThrow();
        assertThat(row.materialId()).isNull();
        assertThat(row.quantity()).isEqualByComparingTo(tile.quantity());
    }

    @Test
    void savingAgainReplacesTheOneRow() {
        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 300, "9", 50, 60, 3));
        TileLayoutView second = calculatorService.saveTileLayout(estimateId, item, ownerId,
                layout(600, 300, "10", 55, 66, 4));
        assertThat(second.tilesToBuy()).isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM estimate_tile_layout WHERE estimate_item_id = ?",
                Integer.class, item)).isEqualTo(1);
    }

    @Test
    void deletingTheLayoutBuysByAreaAgain() {
        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 300, "20", 100, 110, 6));
        calculatorService.deleteTileLayout(estimateId, item, ownerId);
        calculatorService.deleteTileLayout(estimateId, item, ownerId); // idempotent
        assertThat(tileLine(calc()).packages()).isEqualTo(123);
    }

    @Test
    void onlyAnM2WorkLineCanBeLaidOutAndTheRangeMustHold() {
        UUID metres = addWork("Укладання плитки 300х600 (плінтус)", Unit.LINEAR_METER, "10");
        assertThatThrownBy(() -> calculatorService.saveTileLayout(estimateId, metres, ownerId,
                layout(600, 300, "2", 10, 12, 2))).isInstanceOf(TileLayoutValidationException.class);

        UUID item = addWork(TILING_300X600, Unit.M2, "20");
        assertThatThrownBy(() -> calculatorService.saveTileLayout(estimateId, item, ownerId,
                layout(600, 300, "9", 70, 60, 3))).isInstanceOf(TileLayoutValidationException.class);
    }

    @Test
    void aLineOfAnotherEstimateIsNotFound() {
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Інший', 'DRAFT')",
                other, projectId);
        UUID foreign = addWorkTo(other, TILING_300X600, Unit.M2, "20");
        assertThatThrownBy(() -> calculatorService.saveTileLayout(estimateId, foreign, ownerId,
                layout(600, 300, "9", 50, 60, 3))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aLineNoLongerInM2LosesItsLayoutAndARenameKeepsIt() {
        EstimateItemResponse created = estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, TILING_300X600, null, Unit.M2, new BigDecimal("20"), BigDecimal.TEN,
                null, null, false, null, null), ownerId);
        calculatorService.saveTileLayout(estimateId, created.id(), ownerId, layout(600, 300, "9", 50, 60, 3));

        estimateService.updateItem(estimateId, created.id(), new EstimateItemRequest(
                ItemType.WORK, "Укладання плитки 600х600", null, Unit.M2, new BigDecimal("20"), BigDecimal.TEN,
                null, null, false, null, null), ownerId);
        assertThat(layoutCount(created.id())).isEqualTo(1);

        estimateService.updateItem(estimateId, created.id(), new EstimateItemRequest(
                ItemType.WORK, "Укладання плитки 600х600", null, Unit.LINEAR_METER, new BigDecimal("20"),
                BigDecimal.TEN, null, null, false, null, null), ownerId);
        assertThat(layoutCount(created.id())).isZero();
    }

    @Test
    void aDuplicateCarriesTheLayout() {
        EstimateItemResponse created = estimateService.addItem(estimateId, new EstimateItemRequest(
                ItemType.WORK, TILING_300X600, null, Unit.M2, new BigDecimal("20"), BigDecimal.TEN,
                null, null, false, null, null), ownerId);
        calculatorService.saveTileLayout(estimateId, created.id(), ownerId, layout(600, 300, "9.1", 54, 68, 4));

        EstimateResponse copy = estimateService.duplicate(estimateId,
                new EstimateDuplicateRequest(null, BigDecimal.ZERO, false, null), ownerId);
        MaterialCalculationResponse result = calculatorService.calculate(copy.id(), ownerId, TEN, null,
                PerPositionAnswers.NONE);
        assertThat(result.tileLayouts()).singleElement().satisfies(v -> {
            assertThat(v.tilesToBuy()).isEqualTo(72);
            assertThat(v.estimateItemId()).isNotEqualTo(created.id());
        });
    }

    // --- option A: a tiling line in the master's own words -----------------------------------

    @Test
    void anImportedTilingLineIsCountedWithTheLibraryNormsOfItsFormat() {
        // «Перенести в Majstr» brings the master's own names; this one matches no norm by itself.
        addWorkTo(estimateId, "Укладання плитки на підлогу 60×60", Unit.M2, "25");
        MaterialCalculationResponse result = calc();

        CalculatedMaterialLine tile = line(result, "Плитка 600×600");
        // 25 m² × 1,0 × 1,1 = 27,5 m² ÷ 0,36 = 76,4 → 77 tiles — the 600×600 position's own figures.
        assertThat(tile.packages()).isEqualTo(77);
        assertThat(line(result, "Клей для плитки еластичний C2").baseQuantity()).isEqualByComparingTo("212.5");
        assertThat(line(result, "Затирка").baseQuantity()).isEqualByComparingTo("3.75");
        assertThat(result.coverage().trades()).contains("TILING");
    }

    @Test
    void aLineFiledUnderAnotherTradeStillTakesTheTilingNorms() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade)
                VALUES (?, ?, 'WORK', 'Укладання плитки на стіни 30×60', 'M2', 18.5, 100, 100, 0, 'OTHER')
                """, id, estimateId);
        assertThat(line(calc(), "Плитка 300×600").packages()).isEqualTo(114); // 18,5 × 1,1 ÷ 0,18 = 113,1
    }

    @Test
    void aRangeOfFormatsIsNotGuessed() {
        addWorkTo(estimateId, "Укладання плитки на підлогу (до 60×60)", Unit.M2, "25");
        assertThat(calc().materials()).noneMatch(m -> m.name().startsWith("Плитка"));
    }

    @Test
    void aLibraryNameKeepsItsOwnNormsAndALayoutStillWinsForTheTile() {
        UUID item = addWorkTo(estimateId, "Укладання плитки на підлогу 60×60", Unit.M2, "25");
        calculatorService.saveTileLayout(estimateId, item, ownerId, layout(600, 600, "25", 70, 80, 4));
        CalculatedMaterialLine tile = line(calc(), "Плитка 600×600");
        assertThat(tile.packages()).isEqualTo(84);
        assertThat(tile.sources()).singleElement().satisfies(s -> assertThat(s.unit()).isEqualTo(Unit.PIECE));
    }

    // --- helpers ----------------------------------------------------------------------------

    private static TileLayoutRequest layout(int w, int h, String laid, int min, int max, int spare) {
        return new TileLayoutRequest(w, h, new BigDecimal(laid), min, max, spare, "{\"surfaces\":[]}", null);
    }

    private MaterialCalculationResponse calc() {
        return calculatorService.calculate(estimateId, ownerId, TEN, null, PerPositionAnswers.NONE);
    }

    private static CalculatedMaterialLine tileLine(MaterialCalculationResponse r) {
        return line(r, "Плитка 300×600");
    }

    private static CalculatedMaterialLine line(MaterialCalculationResponse r, String prefix) {
        return r.materials().stream().filter(m -> m.name().startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no material " + prefix + " in " + r.materials().stream()
                        .map(CalculatedMaterialLine::name).toList()));
    }

    private int layoutCount(UUID item) {
        return jdbc.queryForObject("SELECT count(*) FROM estimate_tile_layout WHERE estimate_item_id = ?",
                Integer.class, item);
    }

    private UUID addWork(String name, Unit unit, String quantity) {
        return addWorkTo(estimateId, name, unit, quantity);
    }

    private UUID addWorkTo(UUID estimate, String name, Unit unit, String quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade)
                VALUES (?, ?, 'WORK', ?, ?, ?::numeric, 100, 100, ?, 'TILING')
                """, id, estimate, name, unit.name(), quantity, sortOrder++);
        return id;
    }
}
