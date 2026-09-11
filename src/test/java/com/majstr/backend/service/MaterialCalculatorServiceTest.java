package com.majstr.backend.service;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.CalculatedMaterialRow;
import com.majstr.backend.dto.CoverageGapKind;
import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.MaterialLineRequest;
import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateItem;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.MasterMaterialPref;
import com.majstr.backend.entity.Material;
import com.majstr.backend.entity.MaterialNorm;
import com.majstr.backend.entity.MaterialPrefKey;
import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.CatalogItemRepository;
import com.majstr.backend.repository.EstimateItemRepository;
import com.majstr.backend.repository.MasterMaterialPrefRepository;
import com.majstr.backend.repository.MaterialNormRepository;
import com.majstr.backend.repository.MaterialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The calculator's arithmetic, and every way an earlier draft of it got the master the wrong amount
 * of material without anything looking broken.
 *
 * <p>Three of these are regressions on real bugs: a per-м.п. norm run against a per-м.п. position
 * (draft 1 converted units and bought several times too much), a norm surviving a catalog rebuild
 * (draft 2 keyed norms on a catalog id that V116 deletes), and a line whose trade disagrees with
 * the norm's (draft 3 asked only rung 1 and silently proposed nothing). The last two are pinned in
 * {@code DrywallMaterialCalculatorIntegrationTest} and
 * {@code MaterialNormLookupIntegrationTest}, where a real schema can show them.</p>
 */
@ExtendWith(MockitoExtension.class)
class MaterialCalculatorServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ESTIMATE = UUID.randomUUID();
    private static final BigDecimal NO_WASTE = BigDecimal.ZERO;

    @Mock EstimateService estimateService;
    @Mock EstimateItemRepository itemRepository;
    @Mock MaterialNormRepository normRepository;
    @Mock MaterialRepository materialRepository;
    @Mock MasterMaterialPrefRepository prefRepository;
    @Mock CatalogItemRepository catalogItemRepository;
    @Mock ShoppingListService shoppingListService;

    @InjectMocks MaterialCalculatorService service;

    /** What {@link #given} hands back from {@code loadOwned}; a test may sign it first. */
    private final Estimate estimate = new Estimate();

    // --- units: the bug that made the first draft unusable ----------------------------------

    @Test
    void aLinearMetreNormMultipliesALinearMetreQuantityAndNothingIsConverted() {
        Material tape = material("TAPE", "Стрічка-серпянка", Unit.LINEAR_METER, null, null);
        given(item("Заповнення стиків", Unit.LINEAR_METER, "40", Trade.DRYWALL),
                norm(Trade.DRYWALL, "заповнення стиків", Unit.LINEAR_METER, tape, "1.05"));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.unit()).isEqualTo(Unit.LINEAR_METER);
        assertThat(line.baseQuantity()).isEqualByComparingTo("42");
        assertThat(line.sources()).singleElement()
                .satisfies(s -> assertThat(s.unit()).isEqualTo(Unit.LINEAR_METER));
    }

    /** A norm written for m² must never be reached by a position priced per м.п. — same name. */
    @Test
    void aNormForAnotherUnitOfTheSamePositionIsNotUsed() {
        Material glass = material("FIBER", "Склополотно", Unit.M2, null, null);
        given(item("Поклейка склополотна", Unit.LINEAR_METER, "10", Trade.DRYWALL),
                norm(Trade.DRYWALL, "поклейка склополотна", Unit.M2, glass, "1.1"));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.materials()).isEmpty();
        assertThat(result.coverage().covered()).isZero();
        assertThat(result.coverage().gaps()).singleElement()
                .satisfies(g -> assertThat(g.kind()).isEqualTo(CoverageGapKind.NO_NORM));
    }

    // --- accumulation -----------------------------------------------------------------------

    @Test
    void twoPositionsWantingTheSameMaterialBecomeOneRowWithBothSources() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        MaterialNorm walls = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0");
        MaterialNorm ceiling = norm(Trade.DRYWALL, "монтаж на стелю", Unit.M2, sheet, "1.0");
        given(List.of(item("Монтаж на стіни", Unit.M2, "20", Trade.DRYWALL),
                        item("Монтаж на стелю", Unit.M2, "15", Trade.DRYWALL)),
                List.of(walls, ceiling));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.baseQuantity()).isEqualByComparingTo("35");
        assertThat(line.sources()).hasSize(2);
    }

    // --- coverage ---------------------------------------------------------------------------

    @Test
    void aPositionWithNoNormIsNamedInTheCoverageReportInsteadOfBeingSkipped() {
        given(item("Монтаж арки з гіпсокартону", Unit.PIECE, "1", Trade.DRYWALL), (MaterialNorm) null);

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.coverage().total()).isEqualTo(1);
        assertThat(result.coverage().covered()).isZero();
        assertThat(result.coverage().gaps()).singleElement().satisfies(g -> {
            assertThat(g.name()).isEqualTo("Монтаж арки з гіпсокартону");
            assertThat(g.kind()).isEqualTo(CoverageGapKind.NO_NORM);
        });
    }

    /**
     * A surcharge is not work. Counting it would drag the ratio down for a line that could never
     * have a norm, and «12 з 15» has to mean something the master can act on.
     */
    @Test
    void aPercentLineEntersNeitherTheNumeratorNorTheDenominator() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        given(List.of(item("Монтаж на стіни", Unit.M2, "20", Trade.DRYWALL),
                        item("Монтаж на висоті (більше 3м)", Unit.PERCENT, "10", Trade.DRYWALL)),
                List.of(norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0")));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.coverage().total()).isEqualTo(1);
        assertThat(result.coverage().covered()).isEqualTo(1);
        assertThat(result.coverage().gaps()).isEmpty();
    }

    /** A MATERIAL line is what he already decided to buy — explaining it back to him is noise. */
    @Test
    void aMaterialTheMasterAlreadyListedIsNotRecalculated() {
        given(List.of(item(ItemType.MATERIAL, "Лист ГКЛ", Unit.M2, "12", Trade.DRYWALL)), List.of());

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.coverage().total()).isZero();
        assertThat(result.materials()).isEmpty();
    }

    @Test
    void aNormSayingTheWorkConsumesNothingCountsAsCoveredAndBuysNothing() {
        given(item("Демонтаж перегородки з гіпсокартону", Unit.M2, "12", Trade.DRYWALL),
                norm(Trade.DRYWALL, "демонтаж перегородки з гіпсокартону", Unit.M2, null, null));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.coverage().covered()).isEqualTo(1);
        assertThat(result.coverage().gaps()).isEmpty();
        assertThat(result.materials()).isEmpty();
    }

    // --- the lookup ladder ------------------------------------------------------------------

    @Test
    void aLineWithNoTradeAtAllStillFindsItsNorm() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        given(item("Монтаж на стіни", Unit.M2, "20", null),
                norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0"));

        assertThat(only(calculate(NO_WASTE, null)).baseQuantity()).isEqualByComparingTo("20");
    }

    /** V118: a position two trades ship is stored once, so the line's trade may disagree. */
    @Test
    void aTradeDisagreementDoesNotLoseThePosition() {
        Material putty = material("PUTTY", "Шпаклівка фінішна", Unit.KG, null, null);
        given(item("Шпаклювання фінішне", Unit.M2, "30", Trade.PAINTER),
                norm(Trade.DRYWALL, "шпаклювання фінішне", Unit.M2, putty, "1.2"));

        assertThat(only(calculate(NO_WASTE, null)).baseQuantity()).isEqualByComparingTo("36");
    }

    @Test
    void normsFromTwoTradesThatDisagreeLeaveThePositionUncountedAndNamed() {
        Material putty = material("PUTTY", "Шпаклівка", Unit.KG, null, null);
        Material glue = material("GLUE", "Клей", Unit.KG, null, null);
        given(item("Спірна позиція", Unit.M2, "10", null),
                norm(Trade.DRYWALL, "спірна позиція", Unit.M2, putty, "1.0"),
                norm(Trade.TILING, "спірна позиція", Unit.M2, glue, "5.0"));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.materials()).isEmpty();
        assertThat(result.coverage().covered()).isZero();
        assertThat(result.coverage().gaps()).singleElement()
                .satisfies(g -> assertThat(g.kind()).isEqualTo(CoverageGapKind.AMBIGUOUS));
    }

    /** The line's own trade settles it — the disagreement above is only a fallback problem. */
    @Test
    void thePositionsOwnTradeWinsOverAnotherTradesNorm() {
        Material putty = material("PUTTY", "Шпаклівка", Unit.KG, null, null);
        Material glue = material("GLUE", "Клей", Unit.KG, null, null);
        given(item("Спірна позиція", Unit.M2, "10", Trade.TILING),
                norm(Trade.DRYWALL, "спірна позиція", Unit.M2, putty, "1.0"),
                norm(Trade.TILING, "спірна позиція", Unit.M2, glue, "5.0"));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.name()).isEqualTo("Клей");
        assertThat(line.baseQuantity()).isEqualByComparingTo("50");
    }

    // --- waste and rounding -----------------------------------------------------------------

    @Test
    void theWasteAllowanceIsAppliedOnTopOfTheBaseQuantity() {
        Material wool = material("WOOL", "Мінеральна вата", Unit.M2, null, null);
        given(item("Утеплення", Unit.M2, "100", Trade.DRYWALL),
                norm(Trade.DRYWALL, "утеплення", Unit.M2, wool, "1.0"));

        CalculatedMaterialLine line = only(calculate(new BigDecimal("15"), null));

        assertThat(line.wastePercent()).isEqualByComparingTo("15");
        assertThat(line.baseQuantity()).isEqualByComparingTo("100");
        assertThat(line.quantity()).isEqualByComparingTo("115");
    }

    @Test
    void theMastersOwnAllowanceIsUsedWhenTheRequestNamesNone() {
        Material wool = material("WOOL", "Мінеральна вата", Unit.M2, null, null);
        given(item("Утеплення", Unit.M2, "100", Trade.DRYWALL),
                norm(Trade.DRYWALL, "утеплення", Unit.M2, wool, "1.0"));
        when(prefRepository.findByUserIdAndPrefKey(OWNER, MaterialPrefKey.WASTE_PERCENT))
                .thenReturn(Optional.of(pref(MaterialPrefKey.WASTE_PERCENT, "5")));

        MaterialCalculationResponse result = calculate(null, null);

        assertThat(result.wastePercent()).isEqualByComparingTo("5");
        assertThat(only(result).quantity()).isEqualByComparingTo("105");
    }

    @Test
    void withNoAllowanceAnywhereTenPercentIsApplied() {
        Material wool = material("WOOL", "Мінеральна вата", Unit.M2, null, null);
        given(item("Утеплення", Unit.M2, "100", Trade.DRYWALL),
                norm(Trade.DRYWALL, "утеплення", Unit.M2, wool, "1.0"));

        assertThat(calculate(null, null).wastePercent()).isEqualByComparingTo("10");
    }

    /** Half a bag is not sold, and being one bag short stops the work — so rounding is always UP. */
    @Test
    void aPackagedMaterialIsRoundedUpToAWholePackage() {
        Material putty = material("PUTTY", "Шпаклівка фінішна", Unit.KG, "25", "мішок");
        given(item("Шпаклювання", Unit.M2, "23", Trade.DRYWALL),
                norm(Trade.DRYWALL, "шпаклювання", Unit.M2, putty, "1.1"));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.baseQuantity()).isEqualByComparingTo("25.3");
        assertThat(line.packages()).isEqualTo(2);
        assertThat(line.packageName()).isEqualTo("мішок");
        assertThat(line.quantity()).isEqualByComparingTo("50");
    }

    @Test
    void aLooseMaterialIsRoundedUpToAWholeUnit() {
        Material wool = material("WOOL", "Мінеральна вата", Unit.M2, null, null);
        given(item("Утеплення", Unit.M2, "10", Trade.DRYWALL),
                norm(Trade.DRYWALL, "утеплення", Unit.M2, wool, "1.05"));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.baseQuantity()).isEqualByComparingTo("10.5");
        assertThat(line.quantity()).isEqualByComparingTo("11");
        assertThat(line.packages()).isNull();
    }

    /** Exactly one package must not become two — CEILING has to see an exact division. */
    @Test
    void anExactNumberOfPackagesIsNotRoundedUpToOneMore() {
        Material putty = material("PUTTY", "Шпаклівка фінішна", Unit.KG, "25", "мішок");
        given(item("Шпаклювання", Unit.M2, "50", Trade.DRYWALL),
                norm(Trade.DRYWALL, "шпаклювання", Unit.M2, putty, "1.0"));

        assertThat(only(calculate(NO_WASTE, null)).packages()).isEqualTo(2);
    }

    @Test
    void theSheetSizeHabitDecidesHowManySheetsAreBought() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, "3.0", "лист");
        given(item("Монтаж на стіни", Unit.M2, "36", Trade.DRYWALL),
                norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0"));
        when(prefRepository.findByUserIdAndPrefKey(OWNER, MaterialPrefKey.GKL_SHEET))
                .thenReturn(Optional.of(pref(MaterialPrefKey.GKL_SHEET, "1200x3000")));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        // 36 m² of a 3.6 m² sheet is 10, not the 12 the shipped 3.0 m² default would have bought.
        assertThat(line.packageSize()).isEqualByComparingTo("3.6");
        assertThat(line.packages()).isEqualTo(10);
    }

    // --- the perimeter parameter ------------------------------------------------------------

    @Test
    void aPerimeterNormIsReportedAsAMissingParameterRatherThanGuessedFromTheArea() {
        Material track = material("PROFILE_UD", "Профіль UD 27×28", Unit.LINEAR_METER, null, null);
        given(item("Монтаж на стелю", Unit.M2, "20", Trade.DRYWALL),
                perimeterNorm(Trade.DRYWALL, "монтаж на стелю", Unit.M2, track, "1.05"));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.materials()).isEmpty();
        assertThat(result.parameters()).singleElement().satisfies(p -> {
            assertThat(p.parameter()).isEqualTo("PERIMETER");
            assertThat(p.materialName()).isEqualTo("Профіль UD 27×28");
        });
        // The position is still covered: its other materials are known, only this one figure is not.
        assertThat(result.coverage().covered()).isEqualTo(1);
    }

    /** One room, one perimeter: a wall AND a ceiling position must not buy the track twice. */
    @Test
    void aPerimeterNormIsAppliedOncePerEstimateAndTheLargerFigureWins() {
        Material track = material("PROFILE_UD", "Профіль UD 27×28", Unit.LINEAR_METER, null, null);
        given(List.of(item("Монтаж на стелю", Unit.M2, "20", Trade.DRYWALL),
                        item("Монтаж на стіни", Unit.M2, "30", Trade.DRYWALL)),
                List.of(perimeterNorm(Trade.DRYWALL, "монтаж на стелю", Unit.M2, track, "1.05"),
                        perimeterNorm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, track, "2.1")));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, new BigDecimal("20")));

        assertThat(line.baseQuantity()).isEqualByComparingTo("42");
        assertThat(line.sources()).singleElement().satisfies(s -> {
            assertThat(s.basis()).isEqualTo(NormBasis.PERIMETER);
            assertThat(s.quantity()).isEqualByComparingTo("20");
            assertThat(s.estimateItemId()).isNull();
        });
    }

    // --- «моя норма»: the master's own coefficient -------------------------------------------

    /**
     * His own row HIDES the shipped one it was forked from. If both were counted the screen would
     * show the same work buying material twice, with no clue which figure is his.
     */
    @Test
    void theMastersOwnNormHidesTheShippedOneItWasForkedFrom() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        MaterialNorm shipped = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0");
        MaterialNorm mine = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.2");
        mine.setOwner(new User());
        given(List.of(item("Монтаж на стіни", Unit.M2, "10", Trade.DRYWALL)), List.of(shipped, mine));

        CalculatedMaterialLine line = only(calculate(NO_WASTE, null));

        assertThat(line.baseQuantity()).isEqualByComparingTo("12");
        assertThat(line.sources()).singleElement().satisfies(s -> {
            assertThat(s.qtyPerUnit()).isEqualByComparingTo("1.2");
            assertThat(s.normId()).isEqualTo(mine.getId());
            assertThat(s.ownNorm()).isTrue();
        });
    }

    /** Correcting one coefficient must not cost him the rest of the shipped set. */
    @Test
    void aNormForAnotherMaterialOfTheSamePositionIsUntouched() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        Material screws = material("SCREW", "Саморізи", Unit.PIECE, null, null);
        MaterialNorm shippedSheet = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0");
        MaterialNorm shippedScrews = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, screws, "20");
        MaterialNorm mineSheet = norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.2");
        mineSheet.setOwner(new User());
        given(List.of(item("Монтаж на стіни", Unit.M2, "10", Trade.DRYWALL)),
                List.of(shippedSheet, shippedScrews, mineSheet));

        MaterialCalculationResponse result = calculate(NO_WASTE, null);

        assertThat(result.materials()).hasSize(2);
        assertThat(result.materials()).anySatisfy(l -> {
            assertThat(l.name()).isEqualTo("Саморізи");
            assertThat(l.sources()).singleElement()
                    .satisfies(s -> assertThat(s.ownNorm()).isFalse());
        });
    }

    /**
     * A hint, never a gate: the master is told the quantities can still move, and buys or waits as
     * he sees fit.
     */
    @Test
    void theScreenIsToldWhetherTheEstimateBehindTheFiguresIsSettled() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, null, null);
        given(item("Монтаж на стіни", Unit.M2, "10", Trade.DRYWALL),
                norm(Trade.DRYWALL, "монтаж на стіни", Unit.M2, sheet, "1.0"));

        assertThat(calculate(NO_WASTE, null).estimateSigned()).isFalse();

        estimate.setStatus(EstimateStatus.SIGNED);
        assertThat(calculate(NO_WASTE, null).estimateSigned()).isTrue();
    }

    // --- the two outputs --------------------------------------------------------------------

    @Test
    void theNumbersSentToTheShoppingListAreTheOnesTheMasterLeftOnTheScreen() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, "3.0", "лист");
        sheet.setSpec("1200×2500");
        Estimate estimate = new Estimate();
        Project project = new Project();
        project.setId(UUID.randomUUID());
        estimate.setProject(project);
        when(estimateService.loadOwned(ESTIMATE, OWNER)).thenReturn(estimate);
        when(materialRepository.findById(sheet.getId())).thenReturn(Optional.of(sheet));

        service.toShoppingList(ESTIMATE, OWNER, new MaterialApplyRequest(
                List.of(new MaterialLineRequest(sheet.getId(), new BigDecimal("7")))));

        ArgumentCaptor<List<CalculatedMaterialRow>> rows = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(shoppingListService)
                .applyCalculated(eq(project.getId()), eq(OWNER), eq(ESTIMATE), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(r -> {
            assertThat(r.quantity()).isEqualByComparingTo("7");
            assertThat(r.name()).isEqualTo("Лист ГКЛ 1200×2500");
        });
    }

    /** Zeroing a row out on the screen is a removal, not an order for nothing. */
    @Test
    void aRowTheMasterZeroedOutIsNotSent() {
        Material sheet = material("GKL_SHEET", "Лист ГКЛ", Unit.M2, "3.0", "лист");
        Estimate estimate = new Estimate();
        Project project = new Project();
        project.setId(UUID.randomUUID());
        estimate.setProject(project);
        when(estimateService.loadOwned(ESTIMATE, OWNER)).thenReturn(estimate);

        service.toShoppingList(ESTIMATE, OWNER, new MaterialApplyRequest(
                List.of(new MaterialLineRequest(sheet.getId(), BigDecimal.ZERO))));

        ArgumentCaptor<List<CalculatedMaterialRow>> rows = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(shoppingListService)
                .applyCalculated(any(), any(), any(), rows.capture());
        assertThat(rows.getValue()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------

    private MaterialCalculationResponse calculate(BigDecimal waste, BigDecimal perimeter) {
        return service.calculate(ESTIMATE, OWNER, waste, perimeter);
    }

    private CalculatedMaterialLine only(MaterialCalculationResponse result) {
        assertThat(result.materials()).hasSize(1);
        return result.materials().get(0);
    }

    private void given(EstimateItem item, MaterialNorm... norms) {
        List<MaterialNorm> list = new ArrayList<>(Arrays.asList(norms));
        list.removeIf(java.util.Objects::isNull);
        given(List.of(item), list);
    }

    private void given(List<EstimateItem> items, List<MaterialNorm> norms) {
        when(estimateService.loadOwned(ESTIMATE, OWNER)).thenReturn(estimate);
        when(itemRepository.findByEstimateIdOrderBySortOrderAscIdAsc(ESTIMATE)).thenReturn(items);
        if (items.stream().anyMatch(i -> i.getType() == ItemType.WORK && i.getUnit() != Unit.PERCENT)) {
            when(normRepository.findAllByNameKeysForOwner(anyList(), any())).thenReturn(norms);
        }
    }

    private EstimateItem item(String name, Unit unit, String quantity, Trade trade) {
        return item(ItemType.WORK, name, unit, quantity, trade);
    }

    private EstimateItem item(ItemType type, String name, Unit unit, String quantity, Trade trade) {
        return EstimateItem.builder()
                .id(UUID.randomUUID())
                .type(type)
                .name(name)
                .unit(unit)
                .quantity(new BigDecimal(quantity))
                .trade(trade)
                .build();
    }

    private Material material(String code, String name, Unit unit, String packageSize, String packageName) {
        return Material.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .unit(unit)
                .packageSize(packageSize == null ? null : new BigDecimal(packageSize))
                .packageUnit(packageSize == null ? null : unit)
                .packageName(packageName)
                .build();
    }

    private MaterialNorm norm(Trade trade, String nameKey, Unit unit, Material material, String qty) {
        return MaterialNorm.builder()
                .id(UUID.randomUUID())
                .trade(trade)
                .nameKey(nameKey)
                .unit(unit)
                .material(material)
                .qtyPerUnit(qty == null ? null : new BigDecimal(qty))
                .wastePercent(BigDecimal.ZERO)
                .basis(NormBasis.QUANTITY)
                .build();
    }

    private MaterialNorm perimeterNorm(Trade trade, String nameKey, Unit unit, Material material, String qty) {
        MaterialNorm norm = norm(trade, nameKey, unit, material, qty);
        norm.setBasis(NormBasis.PERIMETER);
        return norm;
    }

    private MasterMaterialPref pref(MaterialPrefKey key, String value) {
        return MasterMaterialPref.builder().userId(OWNER).prefKey(key).prefValue(value).build();
    }
}
