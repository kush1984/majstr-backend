package com.majstr.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Electrical points read off a plan — a DRAFT the master verifies before it seeds the
 * chase/cable calculator. Nothing is persisted at parse time.
 *
 * <p><b>A FLAT list of point types</b> (variant 2). The model does NOT group the drawing into
 * rooms and does NOT read room sizes — geometry at scale is exactly the plausible-but-wrong
 * kind of number that would flow into money. It only does the reliable part: COUNT the
 * discrete symbols of each legend type and READ the printed height annotations («h = 900»,
 * mm). The master then distributes those points across rooms in the calculator, where every
 * length is computed deterministically ({@code MeasurementCalc}) from inputs he can see.</p>
 *
 * @param ledStripPresent the plan shows LED strip (drawn as lines). Only a flag: its LENGTH
 *                        is never estimated by the model — the master adds those metres by
 *                        hand, because a measured-from-drawing length is exactly the kind of
 *                        plausible-but-wrong number that would quietly land in money.
 */
public record ElectricalPlanParseResponse(
        List<Point> points,
        boolean ledStripPresent,
        List<String> warnings
) {
    /**
     * @param type       the legend's own wording ("Розетка електрична", "Бра", …)
     * @param count      how many symbols of this type were counted on the plan
     * @param heights    the h= annotations seen for this type, in millimetres (informational —
     *                   seeds the drops' heights in the calculator)
     * @param confidence high | medium | low — low/medium is highlighted for a check
     */
    public record Point(
            String type,
            int count,
            List<BigDecimal> heights,
            String confidence,
            String note
    ) {}
}
