package com.majstr.backend.integration;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.PerPositionAnswers;
import com.majstr.backend.dto.PublicMaterialCalcRequest;
import com.majstr.backend.dto.PublicMaterialCalcRequest.DrywallWork;
import com.majstr.backend.dto.PublicMaterialCalcRequest.Inputs;
import com.majstr.backend.dto.PublicMaterialCalcRequest.PlasterKind;
import com.majstr.backend.dto.PublicMaterialCalcRequest.PlasterMethod;
import com.majstr.backend.dto.PublicMaterialCalcRequest.ScreedKind;
import com.majstr.backend.dto.PublicMaterialCalcRequest.PublicCalculator;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileFormat;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileLayout;
import com.majstr.backend.dto.PublicMaterialCalcResponse;
import com.majstr.backend.service.MaterialCalculatorService;
import com.majstr.backend.service.PublicMaterialCalculatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public calculators: one arithmetic with the estimate screen, anonymous, bounded, rate-limited.
 *
 * <p>The central test builds an ESTIMATE out of the very positions a public answer names, runs the
 * app's own calculation on it, and asserts the two buying lists are the same to the last package —
 * the page on the site may never show a figure the app would not.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicMaterialCalculatorIntegrationTest extends IntegrationTestBase {

    /** One fake client address per test, so the rate-limit test cannot starve the others. */
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialCalculatorService calculatorService;
    @Autowired PublicMaterialCalculatorService publicService;

    private final HttpClient http = HttpClient.newHttpClient();
    private String ip;
    private UUID ownerId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        ip = "203.0.113." + NEXT_IP.getAndIncrement();
        ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, email_canonical, password_hash, full_name, phone,
                                   company_name, referral_code, email_verified)
                VALUES (?, ?, ?, 'x', 'Майстер', '+380', 'ФОП', ?, TRUE)
                """, ownerId, ownerId + "@t.ua", ownerId + "@t.ua", ownerId.toString().substring(0, 8));
        projectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, owner_id, name, address, status)
                VALUES (?, ?, 'Квартира', 'вул. Тестова 1', 'IN_PROGRESS')
                """, projectId, ownerId);
    }

    // ---- one arithmetic ---------------------------------------------------------------------

    @Test
    void everyTileFormatBuysWhatTheSameEstimateBuys() {
        for (TileFormat format : TileFormat.values()) {
            PublicMaterialCalcRequest req = tile(format, "12.5", "15", null);
            assertSameAsEstimate(req, "TILING", null);
        }
    }

    @Test
    void aVisitorsJointRescalesGroutExactlyAsAMastersHabitDoes() {
        PublicMaterialCalcRequest req = tile(TileFormat.T300X300, "10", "10", "5");
        // The master states the same joint as a stored habit; the two must agree.
        jdbc.update("""
                INSERT INTO master_material_pref (id, user_id, pref_key, pref_value, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'TILE_JOINT_MM', '5', now(), now())
                """, ownerId);
        assertSameAsEstimate(req, "TILING", null);
        BigDecimal at25 = groutKg(publicService.calculate(tile(TileFormat.T300X300, "10", "10", null)));
        BigDecimal at5 = groutKg(publicService.calculate(req));
        assertThat(at5).isGreaterThan(at25);
    }

    @Test
    void everyDrywallJobBuysWhatTheSameEstimateBuys() {
        assertSameAsEstimate(drywall(DrywallWork.CEILING, "18", "17", null), "DRYWALL", new BigDecimal("17"));
        assertSameAsEstimate(drywall(DrywallWork.WALL, "30", null, null), "DRYWALL", null);
        assertSameAsEstimate(drywall(DrywallWork.PARTITION, "12", null, 1), "DRYWALL", null);
        assertSameAsEstimate(drywall(DrywallWork.PARTITION, "12", null, 2), "DRYWALL", null);
    }

    /**
     * The visitor types the partition's own area — one face — while the position is normed per m² of
     * sheathing, both faces in it (V127 decision 1). Taken at face value, 12 m² bought 15 m² of board
     * with the allowance where the wall needs about 26.
     */
    @Test
    void aPartitionIsSheathedOnBothFaces() {
        PublicMaterialCalcResponse res = publicService.calculate(drywall(DrywallWork.PARTITION, "12", null, 1));

        CalculatedMaterialLine board = res.materials().stream()
                .filter(m -> m.name().startsWith("Лист ГКЛ")).findFirst().orElseThrow();
        CalculatedMaterialLine studs = res.materials().stream()
                .filter(m -> m.name().contains("CW")).findFirst().orElseThrow();
        assertThat(board.baseQuantity()).isGreaterThanOrEqualTo(new BigDecimal("24"));
        assertThat(studs.baseQuantity()).isEqualByComparingTo("24");
        assertThat(board.sources().getFirst().quantity()).isEqualByComparingTo("24");

        // Two layers: twice the board again, the frame unchanged.
        PublicMaterialCalcResponse two = publicService.calculate(drywall(DrywallWork.PARTITION, "12", null, 2));
        assertThat(two.materials().stream().filter(m -> m.name().startsWith("Лист ГКЛ")).findFirst()
                .orElseThrow().baseQuantity()).isEqualByComparingTo("48");
    }

    /** Every white-listed position still names a library norm that buys something — a rename would silently answer nothing. */
    @Test
    void everyWhiteListedPositionStillBuysSomething() {
        for (TileFormat format : TileFormat.values()) {
            assertThat(publicService.calculate(tile(format, "10", null, null)).materials())
                    .as("TILE %s", format).hasSizeGreaterThanOrEqualTo(3); // primer, adhesive, grout
        }
        for (DrywallWork work : DrywallWork.values()) {
            assertThat(publicService.calculate(drywall(work, "10", "13", null)).materials())
                    .as("DRYWALL %s", work).isNotEmpty();
        }
    }

    @Test
    void aCeilingWithoutItsPerimeterSaysSoInsteadOfGuessing() {
        PublicMaterialCalcResponse res = publicService.calculate(drywall(DrywallWork.CEILING, "18", null, null));
        assertThat(res.missing()).containsExactly("PERIMETER");
        assertThat(res.materials()).noneMatch(m -> m.name().contains("UD"));
    }

    // ---- the tile itself (V171) -------------------------------------------------------------------

    @Test
    void theTileComesFirstInPiecesAndInBoxesWhenTheBoxIsKnown() {
        PublicMaterialCalcResponse res = publicService.calculate(
                tile(TileFormat.T300X600, "10", "10", TileLayout.STRAIGHT, "1.44"));
        CalculatedMaterialLine tile = res.materials().getFirst();
        assertThat(tile.name()).isEqualTo("Плитка 300×600 мм");
        assertThat(tile.baseQuantity()).isEqualByComparingTo("10");
        // 11 m² with the allowance: 61,1 tiles of 0,18 m² -> 62 tiles, 7,6 boxes of 1,44 m² -> 8.
        assertThat(tile.packages()).isEqualTo(62);
        assertThat(tile.quantity()).isEqualByComparingTo("11.16");
        assertThat(tile.boxes()).isEqualTo(8);
        assertThat(tile.boxSize()).isEqualByComparingTo("1.44");
        // The box is the tile's: nothing else on the list is counted in boxes.
        assertThat(res.materials().subList(1, res.materials().size())).allMatch(m -> m.boxes() == null);

        CalculatedMaterialLine noBox = publicService.calculate(
                tile(TileFormat.T300X600, "10", "10", null, null)).materials().getFirst();
        assertThat(noBox.boxes()).isNull();
        assertThat(noBox.packages()).isEqualTo(62);
    }

    @Test
    void aFormatWithNoSingleSizeIsCountedInSquareMetres() {
        CalculatedMaterialLine mosaic = publicService.calculate(
                tile(TileFormat.MOSAIC, "10", "10", null, null)).materials().getFirst();
        assertThat(mosaic.name()).isEqualTo("Мозаїка");
        assertThat(mosaic.packages()).isNull();
        assertThat(mosaic.quantity()).isEqualByComparingTo("11");
    }

    @Test
    void aDiagonalCourseAddsFivePercentToTheTileAndNothingElse() {
        List<CalculatedMaterialLine> straight = publicService.calculate(
                tile(TileFormat.T300X300, "10", "10", TileLayout.STRAIGHT, null)).materials();
        List<CalculatedMaterialLine> diagonal = publicService.calculate(
                tile(TileFormat.T300X300, "10", "10", TileLayout.DIAGONAL, null)).materials();
        assertThat(diagonal.getFirst().baseQuantity()).isEqualByComparingTo("10.5");
        assertThat(diagonal.getFirst().sources().getFirst().qtyPerUnit()).isEqualByComparingTo("1.05");
        assertThat(summary(diagonal.subList(1, diagonal.size())))
                .isEqualTo(summary(straight.subList(1, straight.size())));
    }

    /** The app reads the layout off the estimate's own «по діагоналі» line — the same figure the page applies. */
    @Test
    void anEstimatesDiagonalLineBuysWhatTheDiagonalPageBuys() {
        PublicMaterialCalcRequest req = tile(TileFormat.T300X600, "12.5", "10", TileLayout.DIAGONAL, null);
        UUID estimateId = UUID.randomUUID();
        jdbc.update("INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Ванна', 'DRAFT')",
                estimateId, projectId);
        UUID laying = UUID.randomUUID();
        workLine(estimateId, UUID.randomUUID(), "Ґрунтування поверхні", "12.5", 0);
        workLine(estimateId, laying, "Укладання плитки 300х600", "12.5", 1);
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade, percent_base_kind, percent_base_item_id)
                VALUES (?, ?, 'WORK', 'Укладання плитки по діагоналі (плюс % до м.кв.)', 'PERCENT', 20, 0,
                        0, 2, 'TILING', 'POSITION', ?)
                """, UUID.randomUUID(), estimateId, laying);

        MaterialCalculationResponse app = calculatorService.calculate(estimateId, ownerId,
                new BigDecimal("10"), null, PerPositionAnswers.NONE);
        assertThat(summary(app.materials())).isEqualTo(summary(publicService.calculate(req).materials()));
        assertThat(app.materials().getFirst().baseQuantity()).isEqualByComparingTo("13.125");
    }

    // ---- «Своя норма з упаковки» ------------------------------------------------------------------

    /** A visitor's figure off his bag buys exactly what a master's own norm of the same figure buys. */
    @Test
    void aVisitorsOwnNormBuysWhatAMastersOwnNormBuys() {
        jdbc.update("""
                INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                                           basis, sort_order)
                SELECT gen_random_uuid(), ?, n.trade, n.name_key, n.unit, n.material_id, 3.0, n.basis, n.sort_order
                FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND n.name_key = 'укладання плитки 300х300' AND m.code = 'TILE_ADHESIVE_C1'
                """, ownerId);
        PublicMaterialCalcRequest req = tileWithOwnNorm(TileFormat.T300X300, "12.5", "3.0");
        assertSameAsEstimate(req, "TILING", null);

        List<CalculatedMaterialLine> own = publicService.calculate(req).materials();
        CalculatedMaterialLine adhesive = own.stream().filter(m -> m.name().startsWith("Клей")).findFirst().orElseThrow();
        assertThat(adhesive.baseQuantity()).isEqualByComparingTo("37.5");
        assertThat(adhesive.sources()).allMatch(s -> s.ownNorm() && s.qtyPerUnit().compareTo(new BigDecimal("3.0")) == 0);
        // Only the adhesive moves.
        List<CalculatedMaterialLine> ours = publicService.calculate(tile(TileFormat.T300X300, "12.5", "10", null)).materials();
        assertThat(summary(own.stream().filter(m -> !m.name().startsWith("Клей")).toList()))
                .isEqualTo(summary(ours.stream().filter(m -> !m.name().startsWith("Клей")).toList()));
    }

    @Test
    void anOwnNormOutsideATenthToTenTimesOursIsA400_andDrywallHasNone() throws Exception {
        // 300×300 is 5,2 kg/m²: 0,4 is below a tenth (grams?), 60 is past ten times (the whole bag?).
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","ownNorm":0.4}}
                """, null).statusCode()).isEqualTo(400);
        HttpResponse<String> tooMuch = post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","ownNorm":60}}
                """, null);
        assertThat(tooMuch.statusCode()).isEqualTo(400);
        // The adhesive's figure is per m², so the hint says exactly that and nothing about a layer.
        assertThat(tooMuch.body()).contains("Перевірте одиниці: кг на м².").doesNotContain("на 1 мм");
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","ownNorm":1.6}}
                """, null).statusCode()).isEqualTo(200);
        assertThat(post("""
                {"calculator":"DRYWALL","inputs":{"area":10,"work":"WALL","ownNorm":1.6}}
                """, null).statusCode()).isEqualTo(400);
    }

    /** One diagonal figure for both coverings: the tile's constant is V147's diagonal-laminate norm. */
    @Test
    void theDiagonalFigureIsTheLaminateNorms() {
        BigDecimal laminate = jdbc.queryForObject("""
                SELECT n.qty_per_unit FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND n.name_key = 'укладка ламінату по діагоналі' AND m.code = 'LAMINATE'
                """, BigDecimal.class);
        assertThat(MaterialCalculatorService.DIAGONAL_LAYOUT).isEqualByComparingTo(laminate);
    }

    // ---- plaster and screed --------------------------------------------------------------------

    /**
     * Every variant the two pages can ask names a position the library still SHIPS, and that
     * position buys its own material — a rename would otherwise leave a page answering nothing.
     */
    @Test
    void everyPlasterAndScreedVariantStandsOnALivePositionThatBuysItsMix() {
        record Case(PublicMaterialCalcRequest req, String position, String material) {}
        List<Case> cases = List.of(
                new Case(plaster(PlasterKind.GYPSUM, null, "10", "15"), "Штукатурка стін ручна до 2см", "Штукатурка гіпсова"),
                new Case(plaster(PlasterKind.GYPSUM, null, "10", "20"), "Штукатурка стін ручна до 2см", "Штукатурка гіпсова"),
                new Case(plaster(PlasterKind.GYPSUM, null, "10", "30"), "Штукатурка стін ручна від 2см", "Штукатурка гіпсова"),
                new Case(plaster(PlasterKind.GYPSUM, null, "10", "50"), "Штукатурка стін ручна від 2см", "Штукатурка гіпсова"),
                new Case(plaster(PlasterKind.GYPSUM, null, "10", "55"),
                        "Штукатурка стін ручна понад 5 см (у кілька шарів, по сітці)", "Штукатурка гіпсова"),
                new Case(plaster(PlasterKind.GYPSUM, PlasterMethod.MACHINE, "10", "15"),
                        "Штукатурка стін машинкою до 2см", "Штукатурка гіпсова машинна"),
                new Case(plaster(PlasterKind.GYPSUM, PlasterMethod.MACHINE, "10", "30"),
                        "Штукатурка стін машинкою понад 2см", "Штукатурка гіпсова машинна"),
                new Case(plaster(PlasterKind.CEMENT_LIME, PlasterMethod.MANUAL, "10", "20"),
                        "Штукатурка маякова цементна", "Штукатурка цементно-вапняна"),
                new Case(screed(ScreedKind.CEMENT, "15", "50", null, null, null),
                        "Цементно-піщана стяжка до 20м2", "Стяжка цементна"),
                new Case(screed(ScreedKind.CEMENT, "25", "50", null, null, null),
                        "Цементно-піщана стяжка понад 20м2", "Стяжка цементна"),
                new Case(screed(ScreedKind.SELF_LEVELLING, "20", "5", null, null, null),
                        "Вирівнювання підлоги самовирівнюючим розчином", "Суміш самовирівнювальна"));
        for (Case c : cases) {
            assertThat(shipped(c.position())).as("%s is in the library", c.position()).isTrue();
            CalculatedMaterialLine mix = only(publicService.calculate(c.req()), c.material());
            assertThat(mix.quantity()).as("%s", c.position()).isPositive();
            assertThat(mix.sources()).singleElement().satisfies(s -> assertThat(s.name()).isEqualTo(c.position()));
        }
        for (String step : new String[]{"Грунтування основи перед штукатуркою, армуванням",
                "Ґрунтування основи під стяжку", "Армування підлоги сіткою під стяжку",
                "Плівкова ізоляція під стяжку", "Монтаж демпферної стрічки"}) {
            assertThat(shipped(step)).as("%s is in the library", step).isTrue();
        }
    }

    /** Area × layer × the figure per mm, plus the allowance — the per-mm figure is never pre-multiplied. */
    @Test
    void aLayerIsAreaTimesMillimetresTimesTheFigurePerMillimetre() {
        // V175: hand gypsum is 0,9 kg/m² per mm (Knauf Rotband 8,5 at 10 mm).
        CalculatedMaterialLine gypsum = only(publicService.calculate(
                plaster(PlasterKind.GYPSUM, null, "10", "10", "0")), "Штукатурка гіпсова");
        assertThat(gypsum.baseQuantity()).isEqualByComparingTo("90");
        assertThat(gypsum.packages()).isEqualTo(3); // 30 kg bags

        CalculatedMaterialLine screed = only(publicService.calculate(
                screed(ScreedKind.CEMENT, "10", "50", null, null, null)), "Стяжка цементна");
        assertThat(screed.baseQuantity()).isEqualByComparingTo("1000"); // 10 × 50 × 2,0
        assertThat(screed.quantity()).isEqualByComparingTo("1100");      // +10 %: 44 bags of 25
    }

    @Test
    void anEmptyThicknessTakesThePositionsOwnSuggestion() {
        assertThat(layerOf(plaster(PlasterKind.GYPSUM, null, "10", null), "Штукатурка гіпсова"))
                .isEqualByComparingTo("15");
        assertThat(layerOf(plaster(PlasterKind.GYPSUM, PlasterMethod.MACHINE, "10", null), "Штукатурка гіпсова машинна"))
                .isEqualByComparingTo("15");
        assertThat(layerOf(plaster(PlasterKind.CEMENT_LIME, null, "10", null), "Штукатурка цементно-вапняна"))
                .isEqualByComparingTo("20");
        assertThat(layerOf(screed(ScreedKind.CEMENT, "10", null, null, null, null), "Стяжка цементна"))
                .isEqualByComparingTo("40");
        assertThat(layerOf(screed(ScreedKind.SELF_LEVELLING, "10", null, null, null, null), "Суміш самовирівнювальна"))
                .isEqualByComparingTo("5");
        assertThat(publicService.calculate(plaster(PlasterKind.GYPSUM, null, "10", null)).missing()).isEmpty();
    }

    /** No plaster or screed position carries a primer since V145, so the step is added once — never twice. */
    @Test
    void thePrimerIsOneStepNeverTwo() {
        for (PublicMaterialCalcRequest req : List.of(
                plaster(PlasterKind.GYPSUM, null, "10", "15"),
                plaster(PlasterKind.CEMENT_LIME, null, "10", "20"),
                screed(ScreedKind.CEMENT, "10", "50", true, true, "13"),
                screed(ScreedKind.SELF_LEVELLING, "10", "5", null, null, "13"))) {
            List<CalculatedMaterialLine> primers = publicService.calculate(req).materials().stream()
                    .filter(m -> m.name().startsWith("Ґрунт")).toList();
            assertThat(primers).as("%s", req).singleElement()
                    .satisfies(p -> assertThat(p.sources()).hasSize(1));
        }
    }

    @Test
    void meshAndFilmOnlyWhenTickedAndOnlyUnderACementScreed() throws Exception {
        List<String> plain = names(publicService.calculate(screed(ScreedKind.CEMENT, "10", "50", null, null, null)));
        assertThat(plain).noneMatch(n -> n.startsWith("Сітка") || n.startsWith("Плівка"));

        PublicMaterialCalcResponse both = publicService.calculate(screed(ScreedKind.CEMENT, "10", "50", true, true, null));
        assertThat(only(both, "Сітка армувальна для стяжки").baseQuantity()).isEqualByComparingTo("11");  // 1,1 m²/m²
        assertThat(only(both, "Плівка поліетиленова").baseQuantity()).isEqualByComparingTo("11.5");       // 1,15 m²/m²

        assertThat(post("""
                {"calculator":"SCREED","inputs":{"area":10,"screed":"SELF_LEVELLING","withMesh":true}}
                """, null).statusCode()).isEqualTo(400);
    }

    @Test
    void theDamperTapeIsCountedOnlyWhenThePerimeterIsGiven() {
        PublicMaterialCalcResponse without = publicService.calculate(screed(ScreedKind.CEMENT, "15", "50", null, null, null));
        assertThat(names(without)).noneMatch(n -> n.startsWith("Стрічка демпферна"));
        assertThat(without.missing()).isEmpty();

        PublicMaterialCalcResponse with = publicService.calculate(
                screed(ScreedKind.SELF_LEVELLING, "15", "5", null, null, "16"));
        assertThat(only(with, "Стрічка демпферна").baseQuantity()).isEqualByComparingTo("16.8"); // 16 m × 1,05
        assertThat(with.missing()).isEmpty();
    }

    @Test
    void eachKindHasItsOwnThicknessBound_inUkrainian() throws Exception {
        record Bound(String calculator, String inputs, int status) {}
        for (Bound b : List.of(
                new Bound("PLASTER", "\"plaster\":\"GYPSUM\",\"thicknessMm\":4", 400),
                new Bound("PLASTER", "\"plaster\":\"GYPSUM\",\"thicknessMm\":5", 200),
                new Bound("PLASTER", "\"plaster\":\"GYPSUM\",\"thicknessMm\":60", 200),
                new Bound("PLASTER", "\"plaster\":\"GYPSUM\",\"thicknessMm\":61", 400),
                new Bound("PLASTER", "\"plaster\":\"CEMENT_LIME\",\"thicknessMm\":40", 200),
                new Bound("PLASTER", "\"plaster\":\"CEMENT_LIME\",\"thicknessMm\":41", 400),
                new Bound("SCREED", "\"screed\":\"CEMENT\",\"thicknessMm\":9", 400),
                new Bound("SCREED", "\"screed\":\"CEMENT\",\"thicknessMm\":10", 200),
                new Bound("SCREED", "\"screed\":\"CEMENT\",\"thicknessMm\":100", 200),
                new Bound("SCREED", "\"screed\":\"CEMENT\",\"thicknessMm\":101", 400),
                new Bound("SCREED", "\"screed\":\"SELF_LEVELLING\",\"thicknessMm\":1", 400),
                new Bound("SCREED", "\"screed\":\"SELF_LEVELLING\",\"thicknessMm\":2", 200),
                new Bound("SCREED", "\"screed\":\"SELF_LEVELLING\",\"thicknessMm\":30", 200),
                new Bound("SCREED", "\"screed\":\"SELF_LEVELLING\",\"thicknessMm\":31", 400))) {
            String json = "{\"calculator\":\"" + b.calculator() + "\",\"inputs\":{\"area\":10," + b.inputs() + "}}";
            assertThat(post(json, null).statusCode()).as(json).isEqualTo(b.status());
        }
        HttpResponse<String> refused = post("""
                {"calculator":"PLASTER","inputs":{"area":10,"plaster":"GYPSUM","thicknessMm":61}}
                """, null);
        assertThat(refused.body()).contains("Товщина гіпсової штукатурки — від 5 до 60 мм");
    }

    @Test
    void aMachineIsForGypsumOnly_andAKindMustBeChosen() throws Exception {
        HttpResponse<String> machine = post("""
                {"calculator":"PLASTER","inputs":{"area":10,"plaster":"CEMENT_LIME","method":"MACHINE"}}
                """, null);
        assertThat(machine.statusCode()).isEqualTo(400);
        assertThat(machine.body()).contains("Машинне нанесення рахуємо лише для гіпсової штукатурки");
        assertThat(post("""
                {"calculator":"PLASTER","inputs":{"area":10}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"SCREED","inputs":{"area":10}}
                """, null).statusCode()).isEqualTo(400);
    }

    /** kg/m² per mm off the bag: 0,9 at 10 mm on 10 m² is 90 kg before the allowance, and marked as his. */
    @Test
    void aVisitorsBagFigureIsPerMillimetreAndBoundedLikeTheAdhesive() throws Exception {
        PublicMaterialCalcRequest own = new PublicMaterialCalcRequest(PublicCalculator.PLASTER, new Inputs(
                new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null, new BigDecimal("0.9"), null, null, null,
                PlasterKind.GYPSUM, PlasterMethod.MACHINE, null, new BigDecimal("10"), null, null));
        CalculatedMaterialLine mix = only(publicService.calculate(own), "Штукатурка гіпсова машинна");
        assertThat(mix.baseQuantity()).isEqualByComparingTo("90");
        assertThat(mix.sources()).allMatch(s -> s.ownNorm());

        // Ours is 0,95 per mm for machine gypsum: 0,09 is below a tenth, 9,6 past ten times.
        assertThat(post("""
                {"calculator":"PLASTER","inputs":{"area":10,"plaster":"GYPSUM","method":"MACHINE","ownNorm":0.09}}
                """, null).statusCode()).isEqualTo(400);
        HttpResponse<String> plaster = post("""
                {"calculator":"PLASTER","inputs":{"area":10,"plaster":"GYPSUM","method":"MACHINE","ownNorm":9.6}}
                """, null);
        assertThat(plaster.statusCode()).isEqualTo(400);
        // 9,6 is the bag's per-10-mm figure typed as is — the answer tells him to divide it.
        assertThat(plaster.body()).contains("кг на м² на 1 мм товщини", "поділіть на 10");
        HttpResponse<String> screed = post("""
                {"calculator":"SCREED","inputs":{"area":10,"screed":"CEMENT","ownNorm":25}}
                """, null);
        assertThat(screed.statusCode()).isEqualTo(400);
        assertThat(screed.body()).contains("кг на м² на 1 мм товщини", "поділіть на 10");
        assertThat(post("""
                {"calculator":"SCREED","inputs":{"area":10,"screed":"CEMENT","ownNorm":1.8}}
                """, null).statusCode()).isEqualTo(200);
        HttpResponse<String> drywall = post("""
                {"calculator":"DRYWALL","inputs":{"area":10,"work":"WALL","ownNorm":1.6}}
                """, null);
        assertThat(drywall.statusCode()).isEqualTo(400);
        assertThat(drywall.body()).contains("для гіпсокартону її немає");
    }

    // ---- over HTTP: anonymous, bounded, limited -------------------------------------------------

    @Test
    void answersWithoutAnyAccount() throws Exception {
        HttpResponse<String> res = post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","wastePercent":10}}
                """, null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Клей для плитки").doesNotContain("ownerId");
    }

    @Test
    void aStrangersTokenChangesNothing() throws Exception {
        HttpResponse<String> anonymous = post("""
                {"calculator":"DRYWALL","inputs":{"area":12,"work":"WALL"}}
                """, null);
        HttpResponse<String> withToken = post("""
                {"calculator":"DRYWALL","inputs":{"area":12,"work":"WALL"}}
                """, "Bearer not-a-real-token");
        assertThat(withToken.statusCode()).isIn(200, 401);
        if (withToken.statusCode() == 200) {
            assertThat(withToken.body()).isEqualTo(anonymous.body());
        }
    }

    @Test
    void figuresPastTheirBoundAreA400() throws Exception {
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10001,"format":"T300X300"}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"DRYWALL","inputs":{"area":10,"work":"CEILING","perimeter":1001}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","jointMm":25}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"DRYWALL","inputs":{"area":10,"work":"CEILING","layers":2}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300","boxArea":11}}
                """, null).statusCode()).isEqualTo(400);
        assertThat(post("""
                {"calculator":"PAINT","inputs":{"area":10}}
                """, null).statusCode()).isEqualTo(400);
    }

    @Test
    void thirtyAMinutePerAddressThenA429WithRetryAfter() throws Exception {
        String body = """
                {"calculator":"TILE","inputs":{"area":10,"format":"T300X300"}}
                """;
        for (int i = 0; i < 30; i++) {
            assertThat(post(body, null).statusCode()).as("request %d", i + 1).isEqualTo(200);
        }
        HttpResponse<String> limited = post(body, null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void assertSameAsEstimate(PublicMaterialCalcRequest req, String trade, BigDecimal perimeter) {
        UUID estimateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO estimates (id, project_id, name, status) VALUES (?, ?, 'Кошторис', 'DRAFT')
                """, estimateId, projectId);
        int order = 0;
        for (MaterialCalculatorService.CalcLine line : publicLines(req)) {
            jdbc.update("""
                    INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                                line_total, sort_order, trade)
                    VALUES (?, ?, 'WORK', ?, 'M2', ?, 100, 100, ?, ?)
                    """, UUID.randomUUID(), estimateId, line.name(), line.quantity(), order++, trade);
        }
        MaterialCalculationResponse app = calculatorService.calculate(estimateId, ownerId,
                req.inputs().wastePercent(), perimeter, PerPositionAnswers.NONE);
        PublicMaterialCalcResponse site = publicService.calculate(req);

        assertThat(summary(site.materials())).as("%s", req).isEqualTo(summary(app.materials()));
    }

    private void workLine(UUID estimateId, UUID id, String name, String quantity, int order) {
        jdbc.update("""
                INSERT INTO estimate_items (id, estimate_id, type, name, unit, quantity, unit_price,
                                            line_total, sort_order, trade)
                VALUES (?, ?, 'WORK', ?, 'M2', ?, 100, 100, ?, 'TILING')
                """, id, estimateId, name, new BigDecimal(quantity), order);
    }

    private List<MaterialCalculatorService.CalcLine> publicLines(PublicMaterialCalcRequest req) {
        // The lines the public answer named, read back off its own explanation.
        return publicService.calculate(req).materials().stream()
                .flatMap(m -> m.sources().stream())
                .filter(s -> s.name() != null)
                .map(s -> new MaterialCalculatorService.CalcLine(s.estimateItemId(), s.name(), s.unit(), null, s.quantity(), false))
                .collect(java.util.stream.Collectors.toMap(MaterialCalculatorService.CalcLine::name, l -> l,
                        (a, b) -> a, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private static List<String> summary(List<CalculatedMaterialLine> lines) {
        return lines.stream()
                .map(l -> l.name() + " | " + l.baseQuantity().toPlainString() + " | "
                        + l.quantity().toPlainString() + " | " + l.packages() + " | " + l.wastePercent())
                .toList();
    }

    private static BigDecimal groutKg(PublicMaterialCalcResponse res) {
        return res.materials().stream().filter(m -> m.name().toLowerCase().contains("затирка"))
                .findFirst().orElseThrow().baseQuantity();
    }

    private static PublicMaterialCalcRequest tile(TileFormat format, String area, String waste, String joint) {
        return new PublicMaterialCalcRequest(PublicCalculator.TILE, new Inputs(new BigDecimal(area),
                waste == null ? null : new BigDecimal(waste), format,
                joint == null ? null : new BigDecimal(joint), null, null, null, null, null, null,
                null, null, null, null, null, null));
    }

    private static PublicMaterialCalcRequest tile(TileFormat format, String area, String waste,
                                                  TileLayout layout, String boxArea) {
        return new PublicMaterialCalcRequest(PublicCalculator.TILE, new Inputs(new BigDecimal(area),
                waste == null ? null : new BigDecimal(waste), format, null, layout,
                boxArea == null ? null : new BigDecimal(boxArea), null, null, null, null,
                null, null, null, null, null, null));
    }

    private static PublicMaterialCalcRequest tileWithOwnNorm(TileFormat format, String area, String ownNorm) {
        return new PublicMaterialCalcRequest(PublicCalculator.TILE, new Inputs(new BigDecimal(area),
                new BigDecimal("10"), format, null, null, null, new BigDecimal(ownNorm), null, null, null,
                null, null, null, null, null, null));
    }

    private static PublicMaterialCalcRequest drywall(DrywallWork work, String area, String perimeter, Integer layers) {
        return new PublicMaterialCalcRequest(PublicCalculator.DRYWALL, new Inputs(new BigDecimal(area), null,
                null, null, null, null, null, work, perimeter == null ? null : new BigDecimal(perimeter), layers,
                null, null, null, null, null, null));
    }

    private static PublicMaterialCalcRequest plaster(PlasterKind kind, PlasterMethod method, String area, String mm) {
        return plaster(kind, method, area, mm, "10");
    }

    private static PublicMaterialCalcRequest plaster(PlasterKind kind, PlasterMethod method, String area, String mm,
                                                     String waste) {
        return new PublicMaterialCalcRequest(PublicCalculator.PLASTER, new Inputs(new BigDecimal(area),
                new BigDecimal(waste), null, null, null, null, null, null, null, null,
                kind, method, null, mm == null ? null : new BigDecimal(mm), null, null));
    }

    private static PublicMaterialCalcRequest screed(ScreedKind kind, String area, String mm, Boolean mesh,
                                                    Boolean film, String perimeter) {
        return new PublicMaterialCalcRequest(PublicCalculator.SCREED, new Inputs(new BigDecimal(area),
                new BigDecimal("10"), null, null, null, null, null, null,
                perimeter == null ? null : new BigDecimal(perimeter), null,
                null, null, kind, mm == null ? null : new BigDecimal(mm), mesh, film));
    }

    private static CalculatedMaterialLine only(PublicMaterialCalcResponse res, String namePrefix) {
        List<CalculatedMaterialLine> found = res.materials().stream()
                .filter(m -> m.name().startsWith(namePrefix)).toList();
        assertThat(found).as("a line named %s in %s", namePrefix, names(res)).hasSize(1);
        return found.getFirst();
    }

    private static List<String> names(PublicMaterialCalcResponse res) {
        return res.materials().stream().map(CalculatedMaterialLine::name).toList();
    }

    private BigDecimal layerOf(PublicMaterialCalcRequest req, String material) {
        return only(publicService.calculate(req), material).sources().getFirst().param();
    }

    private boolean shipped(String position) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM catalog_templates WHERE name = ?", Integer.class, position);
        return n != null && n > 0;
    }

    private HttpResponse<String> post(String json, String authorization) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + env.getProperty("local.server.port")
                        + "/api/public/materials/calc"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", ip)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
