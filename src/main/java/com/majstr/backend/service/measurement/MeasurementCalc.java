package com.majstr.backend.service.measurement;

import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.exception.MeasurementException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Server-side result calculator for measurement elements — the source of truth (the
 * client never sets {@code result}). Parses the raw JSON payload per type and computes
 * a metric (m² or м.пог), rounded to 3 decimals, clamped at 0. Negative dimensions are
 * rejected ({@link MeasurementException} → 400). Mirrors the single-line
 * {@code computeMeasure} for SURFACE so both agree.
 */
@Component
public class MeasurementCalc {

    private static final int SCALE = 3;

    private final ObjectMapper objectMapper;

    public MeasurementCalc(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BigDecimal compute(MeasurementType type, JsonNode payload) {
        try {
            return switch (type) {
                case SURFACE -> surface(objectMapper.treeToValue(payload, SurfacePayload.class));
                case PARTITION -> partition(objectMapper.treeToValue(payload, PartitionPayload.class));
                case LINEAR -> linear(objectMapper.treeToValue(payload, LinearPayload.class));
                case ELECTRICAL_POINTS -> points(objectMapper.treeToValue(payload, PointsPayload.class));
                case SHTROBA -> chase(objectMapper.treeToValue(payload, ShtrobaPayload.class));
                case CABLE -> cable(objectMapper.treeToValue(payload, ShtrobaPayload.class));
            };
        } catch (MeasurementException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MeasurementException("error.measurement.invalid");
        }
    }

    // ---- formulas -------------------------------------------------------------

    /**
     * Σ planes − Σ(w·h·n), every dimension in the payload's unit (absent = metres).
     * A plane is any shape ({@link Shapes}); a segment with no {@code shape} is a
     * pre-shapes rectangle stored in metres, so old payloads keep their exact result.
     */
    private static BigDecimal surface(SurfacePayload p) {
        BigDecimal f2 = BigDecimal.valueOf(factor(p.unit())).pow(2);
        BigDecimal base = BigDecimal.ZERO;
        if (p.segments() != null) {
            for (SurfacePayload.Seg s : p.segments()) {
                if (s.shape() == null) {
                    base = base.add(nz(s.l()).multiply(nz(s.w())).multiply(f2));
                } else {
                    base = base.add(
                            BigDecimal.valueOf(Shapes.area(s.shape(), s.mode(), s.values())).multiply(f2));
                }
            }
        }
        BigDecimal sub = BigDecimal.ZERO;
        if (p.openings() != null) {
            for (SurfacePayload.Opening o : p.openings()) {
                int n = o.n() == null ? 1 : Math.max(0, o.n());
                sub = sub.add(nz(o.w()).multiply(nz(o.h())).multiply(BigDecimal.valueOf(n)).multiply(f2));
            }
        }
        return clamp(base.subtract(sub));
    }

    /** Metres per unit. Absent/unknown = metres — what every dimension meant before units. */
    private static double factor(String unit) {
        if (unit == null) {
            return 1;
        }
        return switch (unit) {
            case "MM" -> 0.001;
            case "CM" -> 0.01;
            case "M" -> 1;
            default -> throw new MeasurementException("error.measurement.invalid");
        };
    }

    /** HW·left + HW·right + HD·end + WD·top — the obscured faces of a box/partition. */
    private static BigDecimal partition(PartitionPayload p) {
        BigDecimal h = nz(p.height());
        BigDecimal w = nz(p.width());
        BigDecimal d = nz(p.depth());
        PartitionPayload.Faces f = p.faces() != null ? p.faces() : DEFAULT_FACES;
        BigDecimal r = BigDecimal.ZERO;
        if (f.left()) r = r.add(h.multiply(w));
        if (f.right()) r = r.add(h.multiply(w));
        if (f.end()) r = r.add(h.multiply(d));
        if (f.top()) r = r.add(w.multiply(d));
        return clamp(r);
    }

    /** (H·left + H·right + W·top + W·bottom) · qty — reveal/skirting perimeter × count. */
    private static BigDecimal linear(LinearPayload p) {
        BigDecimal h = nz(p.height());
        BigDecimal w = nz(p.width());
        LinearPayload.Sides s = p.sides() != null ? p.sides() : DEFAULT_SIDES;
        int qty = p.qty() == null ? 1 : Math.max(0, p.qty());
        BigDecimal per = BigDecimal.ZERO;
        if (s.left()) per = per.add(h);
        if (s.right()) per = per.add(h);
        if (s.top()) per = per.add(w);
        if (s.bottom()) per = per.add(w);
        return clamp(per.multiply(BigDecimal.valueOf(qty)));
    }

    /** Σ of the per-type counts — discrete points off a plan. Unit is шт, so no scaling. */
    private static BigDecimal points(PointsPayload p) {
        int total = 0;
        if (p.points() != null) {
            for (PointsPayload.Row r : p.points()) {
                total += r.count() == null ? 0 : Math.max(0, r.count());
            }
        }
        return clamp(BigDecimal.valueOf(total));
    }

    /**
     * Two lengths from ONE input (a shared {@link ShtrobaPayload}), both in metres — millimetre
     * inputs (h=300 socket, h=2600 A/C outlet) divided by 1000:
     *
     * <ul>
     *   <li><b>CABLE</b> = bus length + Σ ALL drops, then + reserve % — the physical wire is pulled
     *       to every point regardless of chasing, and needs slack.</li>
     *   <li><b>SHTROBA</b> (chase) = only what is actually cut: the bus if {@code busChase}, plus the
     *       drops whose point is flagged {@code chase}. No reserve — a chase is cut to size. A bus
     *       running along the ceiling, or a room without plaster, is simply left unflagged.</li>
     * </ul>
     *
     * <p>bus level = {@code busLevel} when it runs along the top, else 0 (floor); a point's drop
     * = |bus level − its height| × qty. The bus length is explicit ({@code busLength}) — never
     * guessed from the drawing.</p>
     */
    private static BigDecimal cable(ShtrobaPayload p) {
        BigDecimal level = busLevel(p);
        BigDecimal mm = nz(p.busLength());
        if (p.points() != null) {
            for (ShtrobaPayload.Point pt : p.points()) {
                mm = mm.add(drop(pt, level)); // cable reaches every point
            }
        }
        BigDecimal reserve = nz(p.reservePct());
        mm = mm.multiply(BigDecimal.ONE.add(reserve.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        return mmToMetres(mm);
    }

    private static BigDecimal chase(ShtrobaPayload p) {
        BigDecimal level = busLevel(p);
        BigDecimal mm = BigDecimal.ZERO;
        // Bus is chased only when flagged (null = yes, the common case; ceiling bus → false).
        if (p.busChase() == null || p.busChase()) {
            mm = mm.add(nz(p.busLength()));
        }
        if (p.points() != null) {
            for (ShtrobaPayload.Point pt : p.points()) {
                if (pt.chase() == null || pt.chase()) { // per-drop: an un-plastered wall isn't chased
                    mm = mm.add(drop(pt, level));
                }
            }
        }
        return mmToMetres(mm);
    }

    private static BigDecimal busLevel(ShtrobaPayload p) {
        boolean fromTop = p.busFromTop() == null || p.busFromTop();
        return fromTop ? nz(p.busLevel()) : BigDecimal.ZERO;
    }

    private static BigDecimal drop(ShtrobaPayload.Point pt, BigDecimal level) {
        int qty = pt.qty() == null ? 1 : Math.max(0, pt.qty());
        return level.subtract(nz(pt.h())).abs().multiply(BigDecimal.valueOf(qty));
    }

    private static BigDecimal mmToMetres(BigDecimal mm) {
        return clamp(mm.divide(BigDecimal.valueOf(1000), SCALE, RoundingMode.HALF_UP));
    }

    // ---- helpers --------------------------------------------------------------

    private static final PartitionPayload.Faces DEFAULT_FACES =
            new PartitionPayload.Faces(true, true, true, false); // 2 sides + end
    private static final LinearPayload.Sides DEFAULT_SIDES =
            new LinearPayload.Sides(true, true, true, false); // window reveal: no bottom

    /** Non-negative dimension; a negative value is a validation error. Null → 0. */
    private static BigDecimal nz(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v.signum() < 0) {
            throw new MeasurementException("error.measurement.invalid");
        }
        return v;
    }

    private static BigDecimal clamp(BigDecimal v) {
        return v.max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ---- payload shapes (deserialized from the raw JSON per type) -------------

    /**
     * @param unit     length unit of every dimension below; null = metres (pre-shapes payloads)
     * @param segments the planes the surface is made of
     */
    public record SurfacePayload(String unit, List<Seg> segments, List<Opening> openings) {
        /**
         * @param l     legacy rectangle side, metres — set only on pre-shapes rows
         * @param w     legacy rectangle side, metres
         * @param shape null on a legacy row; otherwise the shape key (see Shapes)
         */
        public record Seg(BigDecimal l, BigDecimal w, String shape, String mode,
                          Map<String, Double> values) {}

        public record Opening(BigDecimal w, BigDecimal h, Integer n) {}
    }

    public record PartitionPayload(BigDecimal height, BigDecimal width, BigDecimal depth, Faces faces) {
        public record Faces(boolean left, boolean right, boolean end, boolean top) {}
    }

    public record LinearPayload(BigDecimal height, BigDecimal width, Sides sides, Integer qty) {
        public record Sides(boolean left, boolean right, boolean top, boolean bottom) {}
    }

    /** Electrical points grouped by type, as read off a plan's legend. */
    public record PointsPayload(List<Row> points) {
        /**
         * @param heights the h= annotations seen for this type (informational, kept for the
         *                estimate/чорнова wiring — they do not affect the count)
         */
        public record Row(String type, Integer count, List<BigDecimal> heights, String note) {}
    }

    /**
     * Chase/cable input — ONE payload drives both the CABLE and SHTROBA results. All lengths in
     * MILLIMETRES (as annotated on plans).
     *
     * @param busLevel   height of the horizontal bus above the finished floor
     * @param busFromTop true = bus runs along the top (level = busLevel); false = along the
     *                   floor (level = 0). A per-room choice: ground floors are usually chased
     *                   from the ceiling, upper floors from the floor.
     * @param busLength  explicit length of the horizontal bus (магістраль), mm — set by the
     *                   master, never guessed off the drawing.
     * @param busChase   whether the bus itself is chased (false when it runs along the ceiling).
     *                   Null = yes.
     * @param reservePct slack added to the CABLE only (a chase is cut to size).
     */
    public record ShtrobaPayload(BigDecimal busLevel, Boolean busFromTop, BigDecimal busLength,
                                 Boolean busChase, BigDecimal reservePct, List<Point> points) {
        /**
         * @param kind  label only (socket/switch/light/outlet) — does not affect the maths
         * @param h     height above the finished floor
         * @param chase whether THIS drop is chased (false for an un-plastered wall). Null = yes.
         */
        public record Point(String kind, String name, BigDecimal h, Integer qty, Boolean chase) {}
    }
}
