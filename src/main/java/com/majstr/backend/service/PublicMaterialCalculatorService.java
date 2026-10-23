package com.majstr.backend.service;

import com.majstr.backend.dto.CalculatedMaterialLine;
import com.majstr.backend.dto.MissingParameter;
import com.majstr.backend.dto.PublicMaterialCalcRequest;
import com.majstr.backend.dto.PublicMaterialCalcRequest.DrywallWork;
import com.majstr.backend.dto.PublicMaterialCalcRequest.Inputs;
import com.majstr.backend.dto.PublicMaterialCalcRequest.PlasterKind;
import com.majstr.backend.dto.PublicMaterialCalcRequest.PlasterMethod;
import com.majstr.backend.dto.PublicMaterialCalcRequest.ScreedKind;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileFormat;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileLayout;
import com.majstr.backend.dto.PublicMaterialCalcResponse;
import com.majstr.backend.entity.NormBasis;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.PublicCalcValidationException;
import com.majstr.backend.service.MaterialCalculatorService.CalcLine;
import com.majstr.backend.service.MaterialCalculatorService.Computed;
import com.majstr.backend.service.MaterialCalculatorService.OwnNorm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public calculators on the site — «Плитка», «Гіпсокартон», «Штукатурка» and «Стяжка» — as a
 * WHITE LIST of library positions run through {@link MaterialCalculatorService#calculateLines}, the
 * estimate screen's own arithmetic.
 *
 * <p>Which positions stand behind an answer is decided HERE and nowhere else: the request names a
 * calculator and answers its questions, it never names a position. The names below are the
 * library's character for character — a norm is found by name + unit, so a renamed position would
 * quietly answer nothing; {@code PublicMaterialCalculatorIntegrationTest} pins that each one is
 * still in the library and still buys something.</p>
 *
 * <p>Nothing is stored, nothing is logged: the visitor is anonymous and his figures are his.</p>
 */
@Service
@RequiredArgsConstructor
public class PublicMaterialCalculatorService {

    static final Map<TileFormat, String> TILE_POSITIONS = Map.of(
            TileFormat.T100X100, "Укладання плитки 100х100",
            TileFormat.T200X200, "Укладання плитки 200х200",
            TileFormat.T300X300, "Укладання плитки 300х300",
            TileFormat.T300X600, "Укладання плитки 300х600",
            TileFormat.T600X600, "Укладання плитки 600х600",
            TileFormat.T600X1200, "Укладання плитки 600х1200",
            TileFormat.T800X1600, "Укладання плитки 800х1600",
            TileFormat.T1200X2400, "Укладання плитки 1200х2400 мм",
            TileFormat.MOSAIC, "Укладання мозаїки",
            TileFormat.METRO, "Укладання плитки кабанчик, «цегла»");

    /** The deep primer goes on before any of them — its own step, as in the bundles (TRADES-REVIEW §1.9). */
    static final String TILE_PRIMER = "Ґрунтування поверхні";

    static final String DRYWALL_CEILING = "Монтаж гіпсокартону на стелю рівну";
    static final String DRYWALL_WALL = "Монтаж гіпсокартону на стіни";
    static final String DRYWALL_PARTITION_1 = "Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 1 шар";
    static final String DRYWALL_PARTITION_2 = "Монтаж конструкцій (перегородки 2 сторони) із гіпсокартону в 2 шари";
    private static final BigDecimal PARTITION_FACES = BigDecimal.valueOf(2);

    // Plaster. The bands are the library's own positions; within one material they share a
    // coefficient, so the band decides the NAME the explanation shows and the suggestion an empty
    // field takes — never a different figure.
    static final String PLASTER_GYPSUM_TO_20 = "Штукатурка стін ручна до 2см";
    static final String PLASTER_GYPSUM_TO_50 = "Штукатурка стін ручна від 2см";
    static final String PLASTER_GYPSUM_OVER_50 = "Штукатурка стін ручна понад 5 см (у кілька шарів, по сітці)";
    static final String PLASTER_MACHINE_TO_20 = "Штукатурка стін машинкою до 2см";
    static final String PLASTER_MACHINE_OVER_20 = "Штукатурка стін машинкою понад 2см";
    /** Hand-applied cement-lime (1,6, Kreisel 1,5-1,7) — the PAINTER position's 1,4 is the machine range (V175). */
    static final String PLASTER_CEMENT_LIME = "Штукатурка маякова цементна";
    /** No plaster position carries a primer since V145 (primer is bought once), so it is its own step. */
    static final String PLASTER_PRIMER = "Грунтування основи перед штукатуркою, армуванням";

    // Screed. Both cement positions carry the same norm; the area picks the honest name.
    static final String SCREED_CEMENT_TO_20 = "Цементно-піщана стяжка до 20м2";
    static final String SCREED_CEMENT_OVER_20 = "Цементно-піщана стяжка понад 20м2";
    static final String SCREED_SELF_LEVELLING = "Вирівнювання підлоги самовирівнюючим розчином";
    static final String SCREED_PRIMER = "Ґрунтування основи під стяжку";
    static final String SCREED_MESH = "Армування підлоги сіткою під стяжку";
    static final String SCREED_FILM = "Плівкова ізоляція під стяжку";
    /** Normed on the PERIMETER (1,05 m per m), so it is only added when the visitor gave one. */
    static final String SCREED_DAMPER_TAPE = "Монтаж демпферної стрічки";

    private static final BigDecimal TWENTY = BigDecimal.valueOf(20);
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);

    /** The tile calculator's main material — whichever adhesive class the format takes. */
    static final String TILE_MAIN_MATERIAL = "TILE_ADHESIVE_";
    private static final BigDecimal OWN_NORM_MIN = new BigDecimal("0.1");
    private static final BigDecimal OWN_NORM_MAX = BigDecimal.TEN;

    private final MaterialCalculatorService calculator;

    /**
     * What one calculator turned the visitor's answers into: the positions, the per-position
     * answers (a layer's millimetres), the perimeter, and the material a «своя норма» stands in for
     * (null = this calculator takes none).
     */
    private record Job(List<CalcLine> lines, Map<NormBasis, Map<UUID, BigDecimal>> perPosition,
                       BigDecimal perimeter, String mainMaterial) {
        static Job of(List<CalcLine> lines, BigDecimal perimeter, String mainMaterial) {
            return new Job(lines, new EnumMap<>(NormBasis.class), perimeter, mainMaterial);
        }
    }

    public PublicMaterialCalcResponse calculate(PublicMaterialCalcRequest req) {
        Inputs in = req.inputs();
        Job job = switch (req.calculator()) {
            case TILE -> Job.of(tile(in), null, TILE_MAIN_MATERIAL);
            case DRYWALL -> Job.of(drywall(in), in.perimeter(), null);
            case PLASTER -> plaster(in);
            case SCREED -> screed(in);
        };
        BigDecimal joint = req.calculator() == PublicMaterialCalcRequest.PublicCalculator.TILE
                ? in.jointMm() : null;
        BigDecimal box = req.calculator() == PublicMaterialCalcRequest.PublicCalculator.TILE
                ? in.boxArea() : null;
        Computed computed = calculator.calculateLines(job.lines(), in.wastePercent(), job.perimeter(),
                job.perPosition(), joint, box, ownNorm(in, job));
        // Every line the page prints carries a quantity: a line that came to nothing is not a purchase.
        List<CalculatedMaterialLine> materials = computed.materials().stream()
                .filter(m -> m.quantity() != null && m.quantity().signum() > 0)
                .toList();
        BigDecimal waste = materials.isEmpty() ? null : materials.getFirst().wastePercent();
        return new PublicMaterialCalcResponse(
                materials,
                waste,
                computed.parameters().stream().map(MissingParameter::parameter).distinct().toList());
    }

    /**
     * «Своя норма з упаковки»: the visitor's figure for the calculator's MAIN material — the tile
     * adhesive (kg/m²), the plaster or the screed mix (kg/m² per mm, which is how a bag states it).
     * The drywall calculator has no such material, so there it is refused rather than ignored.
     *
     * <p>Bounded RELATIVE to our figure, 0,1-10×: a figure from a real bag sits well inside that,
     * while a value typed in grams or for the whole bag falls outside it and is a 400. Our figure
     * for a THICKNESS norm is per millimetre, never already multiplied by the layer.</p>
     */
    private OwnNorm ownNorm(Inputs in, Job job) {
        BigDecimal value = in.ownNorm();
        if (value == null) {
            return null;
        }
        if (job.mainMaterial() == null) {
            throw new PublicCalcValidationException("error.public-calc.own-norm-unsupported");
        }
        BigDecimal ours = calculator.libraryCoefficient(job.lines(), job.mainMaterial()).orElseThrow();
        if (value.compareTo(ours.multiply(OWN_NORM_MIN)) < 0 || value.compareTo(ours.multiply(OWN_NORM_MAX)) > 0) {
            throw new PublicCalcValidationException("error.public-calc.own-norm-range");
        }
        return new OwnNorm(job.mainMaterial(), value);
    }

    private List<CalcLine> tile(Inputs in) {
        if (in.format() == null) {
            throw new PublicCalcValidationException("error.public-calc.format-required");
        }
        List<CalcLine> lines = new ArrayList<>();
        lines.add(line(TILE_PRIMER, Trade.TILING, in.area(), false));
        lines.add(line(TILE_POSITIONS.get(in.format()), Trade.TILING, in.area(),
                in.layout() == TileLayout.DIAGONAL));
        return lines;
    }

    private List<CalcLine> drywall(Inputs in) {
        if (in.work() == null) {
            throw new PublicCalcValidationException("error.public-calc.work-required");
        }
        int layers = in.layers() == null ? 1 : in.layers();
        if (layers == 2 && in.work() != DrywallWork.PARTITION) {
            // The library has a two-layer position for a partition only; for a ceiling or a wall
            // lining a second layer would be a coefficient we made up, not one we checked.
            throw new PublicCalcValidationException("error.public-calc.layers-partition-only");
        }
        String position = switch (in.work()) {
            case CEILING -> DRYWALL_CEILING;
            case WALL -> DRYWALL_WALL;
            case PARTITION -> layers == 2 ? DRYWALL_PARTITION_2 : DRYWALL_PARTITION_1;
        };
        // A «перегородки 2 сторони» position is priced and normed per m² of SHEATHING, both faces
        // already in it (V127 decision 1); the visitor types the partition's own area, one face.
        BigDecimal quantity = in.work() == DrywallWork.PARTITION ? in.area().multiply(PARTITION_FACES) : in.area();
        return List.of(line(position, Trade.DRYWALL, quantity, false));
    }

    private Job plaster(Inputs in) {
        if (in.plaster() == null) {
            throw new PublicCalcValidationException("error.public-calc.plaster-required");
        }
        PlasterMethod method = in.method() == null ? PlasterMethod.MANUAL : in.method();
        BigDecimal mm = in.thicknessMm();
        CalcLine main;
        String material;
        if (in.plaster() == PlasterKind.CEMENT_LIME) {
            if (method == PlasterMethod.MACHINE) {
                // The library's only machine plaster is gypsum; a cement-lime one would be a figure we
                // did not check.
                throw new PublicCalcValidationException("error.public-calc.machine-gypsum-only");
            }
            requireThickness(mm, 5, 40, "error.public-calc.thickness-range-cement-lime");
            main = line(PLASTER_CEMENT_LIME, Trade.TILING, in.area(), false);
            material = "PLASTER_CEMENT_LIME";
        } else {
            requireThickness(mm, 5, 60, "error.public-calc.thickness-range-gypsum");
            // An empty field takes the first band's suggestion (15 mm), the usual wall.
            boolean to20 = mm == null || mm.compareTo(TWENTY) <= 0;
            String position = method == PlasterMethod.MACHINE
                    ? (to20 ? PLASTER_MACHINE_TO_20 : PLASTER_MACHINE_OVER_20)
                    : (to20 ? PLASTER_GYPSUM_TO_20
                        : mm.compareTo(FIFTY) <= 0 ? PLASTER_GYPSUM_TO_50 : PLASTER_GYPSUM_OVER_50);
            main = line(position, Trade.PAINTER, in.area(), false);
            material = method == PlasterMethod.MACHINE ? "PLASTER_GYPSUM_MACHINE" : "PLASTER_GYPSUM";
        }
        List<CalcLine> lines = List.of(line(PLASTER_PRIMER, Trade.PAINTER, in.area(), false), main);
        Job job = Job.of(lines, null, material);
        layer(job, main, mm);
        return job;
    }

    private Job screed(Inputs in) {
        if (in.screed() == null) {
            throw new PublicCalcValidationException("error.public-calc.screed-required");
        }
        boolean cement = in.screed() == ScreedKind.CEMENT;
        boolean mesh = Boolean.TRUE.equals(in.withMesh());
        boolean film = Boolean.TRUE.equals(in.withFilm());
        if (!cement && (mesh || film)) {
            throw new PublicCalcValidationException("error.public-calc.cement-screed-only");
        }
        BigDecimal mm = in.thicknessMm();
        CalcLine main;
        if (cement) {
            requireThickness(mm, 10, 100, "error.public-calc.thickness-range-screed");
            main = line(in.area().compareTo(TWENTY) <= 0 ? SCREED_CEMENT_TO_20 : SCREED_CEMENT_OVER_20,
                    Trade.FLOORING, in.area(), false);
        } else {
            requireThickness(mm, 2, 30, "error.public-calc.thickness-range-self-levelling");
            main = line(SCREED_SELF_LEVELLING, Trade.FLOORING, in.area(), false);
        }
        List<CalcLine> lines = new ArrayList<>();
        lines.add(line(SCREED_PRIMER, Trade.FLOORING, in.area(), false));
        if (film) {
            lines.add(line(SCREED_FILM, Trade.FLOORING, in.area(), false));
        }
        if (mesh) {
            lines.add(line(SCREED_MESH, Trade.FLOORING, in.area(), false));
        }
        lines.add(main);
        if (in.perimeter() != null) {
            // The tape's norm reads the perimeter, not this quantity; the line only names the position.
            lines.add(line(SCREED_DAMPER_TAPE, Trade.FLOORING, in.area(), false));
        }
        Job job = Job.of(lines, in.perimeter(), cement ? "SCREED_CEMENT" : "SELF_LEVELLING");
        layer(job, main, mm);
        return job;
    }

    /** A thickness the visitor gave must fit the kind; an empty one is the position's own suggestion. */
    private static void requireThickness(BigDecimal mm, int min, int max, String key) {
        if (mm != null && (mm.compareTo(BigDecimal.valueOf(min)) < 0 || mm.compareTo(BigDecimal.valueOf(max)) > 0)) {
            throw new PublicCalcValidationException(key);
        }
    }

    /** The layer's millimetres for the main position — his figure, or the library's suggestion. */
    private void layer(Job job, CalcLine main, BigDecimal mm) {
        BigDecimal thickness = mm != null ? mm : calculator.librarySuggestion(main, NormBasis.THICKNESS).orElse(null);
        if (thickness != null) {
            // Left unanswered (a renamed position), the response names THICKNESS as missing.
            job.perPosition().put(NormBasis.THICKNESS, Map.of(main.id(), thickness));
        }
    }

    private static CalcLine line(String name, Trade trade, BigDecimal quantity, boolean diagonal) {
        // A stable id per position, so a missing per-position answer would name the same line every time.
        UUID id = UUID.nameUUIDFromBytes(("public-calc:" + name).getBytes(StandardCharsets.UTF_8));
        return new CalcLine(id, name, Unit.M2, trade, quantity, diagonal);
    }
}
