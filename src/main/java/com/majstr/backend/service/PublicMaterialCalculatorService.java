package com.majstr.backend.service;

import com.majstr.backend.dto.PublicMaterialCalcRequest;
import com.majstr.backend.dto.PublicMaterialCalcRequest.DrywallWork;
import com.majstr.backend.dto.PublicMaterialCalcRequest.Inputs;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileFormat;
import com.majstr.backend.dto.PublicMaterialCalcRequest.TileLayout;
import com.majstr.backend.dto.MissingParameter;
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
 * The public calculators on the site — «Плитка» and «Гіпсокартон» — as a WHITE LIST of library
 * positions run through {@link MaterialCalculatorService#calculateLines}, the estimate screen's
 * own arithmetic.
 *
 * <p>Which positions stand behind an answer is decided HERE and nowhere else: the request names a
 * calculator and answers its questions, it never names a position. The names below are the
 * library's character for character — a norm is found by name + unit, so a renamed position would
 * quietly answer nothing; {@code PublicMaterialCalculatorIntegrationTest} pins that each one still
 * buys something.</p>
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

    /** The tile calculator's main material — whichever adhesive class the format takes. */
    static final String TILE_MAIN_MATERIAL = "TILE_ADHESIVE_";
    private static final BigDecimal OWN_NORM_MIN = new BigDecimal("0.1");
    private static final BigDecimal OWN_NORM_MAX = BigDecimal.TEN;

    private final MaterialCalculatorService calculator;

    public PublicMaterialCalcResponse calculate(PublicMaterialCalcRequest req) {
        Inputs in = req.inputs();
        List<CalcLine> lines = switch (req.calculator()) {
            case TILE -> tile(in);
            case DRYWALL -> drywall(in);
        };
        BigDecimal joint = req.calculator() == PublicMaterialCalcRequest.PublicCalculator.TILE
                ? in.jointMm() : null;
        BigDecimal perimeter = req.calculator() == PublicMaterialCalcRequest.PublicCalculator.DRYWALL
                ? in.perimeter() : null;
        BigDecimal box = req.calculator() == PublicMaterialCalcRequest.PublicCalculator.TILE
                ? in.boxArea() : null;
        Computed computed = calculator.calculateLines(lines, in.wastePercent(), perimeter,
                new EnumMap<>(NormBasis.class), joint, box, ownNorm(req, lines));
        BigDecimal waste = computed.materials().isEmpty()
                ? null
                : computed.materials().getFirst().wastePercent();
        return new PublicMaterialCalcResponse(
                computed.materials(),
                waste,
                computed.parameters().stream().map(MissingParameter::parameter).distinct().toList());
    }


    /**
     * «Своя норма з упаковки»: the visitor's kg/m² for the calculator's MAIN material — the tile
     * adhesive, C1 or C2 by format. The drywall calculator has no such material (its finishing
     * putty is not counted), so there it is refused rather than ignored.
     *
     * <p>Bounded RELATIVE to our figure, 0,1-10×: a figure from a real bag sits well inside that,
     * while a value typed in grams or for the whole bag falls outside it and is a 400.</p>
     */
    private OwnNorm ownNorm(PublicMaterialCalcRequest req, List<CalcLine> lines) {
        BigDecimal value = req.inputs().ownNorm();
        if (value == null) {
            return null;
        }
        if (req.calculator() != PublicMaterialCalcRequest.PublicCalculator.TILE) {
            throw new PublicCalcValidationException("error.public-calc.own-norm-tile-only");
        }
        BigDecimal ours = calculator.libraryCoefficient(lines, TILE_MAIN_MATERIAL).orElseThrow();
        if (value.compareTo(ours.multiply(OWN_NORM_MIN)) < 0 || value.compareTo(ours.multiply(OWN_NORM_MAX)) > 0) {
            throw new PublicCalcValidationException("error.public-calc.own-norm-range");
        }
        return new OwnNorm(TILE_MAIN_MATERIAL, value);
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
        return List.of(line(position, Trade.DRYWALL, in.area(), false));
    }

    private static CalcLine line(String name, Trade trade, BigDecimal quantity, boolean diagonal) {
        // A stable id per position, so a missing per-position answer would name the same line every time.
        UUID id = UUID.nameUUIDFromBytes(("public-calc:" + name).getBytes(StandardCharsets.UTF_8));
        return new CalcLine(id, name, Unit.M2, trade, quantity, diagonal);
    }
}
