package com.majstr.backend.service.measurement;

import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.exception.MeasurementException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
            };
        } catch (MeasurementException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MeasurementException("error.measurement.invalid");
        }
    }

    // ---- formulas -------------------------------------------------------------

    /** Σ(l·w) − Σ(w·h·n). Same as the single-line area calculator. */
    private static BigDecimal surface(SurfacePayload p) {
        BigDecimal base = BigDecimal.ZERO;
        if (p.segments() != null) {
            for (SurfacePayload.Seg s : p.segments()) {
                base = base.add(nz(s.l()).multiply(nz(s.w())));
            }
        }
        BigDecimal sub = BigDecimal.ZERO;
        if (p.openings() != null) {
            for (SurfacePayload.Opening o : p.openings()) {
                int n = o.n() == null ? 1 : Math.max(0, o.n());
                sub = sub.add(nz(o.w()).multiply(nz(o.h())).multiply(BigDecimal.valueOf(n)));
            }
        }
        return clamp(base.subtract(sub));
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

    public record SurfacePayload(List<Seg> segments, List<Opening> openings) {
        public record Seg(BigDecimal l, BigDecimal w) {}
        public record Opening(BigDecimal w, BigDecimal h, Integer n) {}
    }

    public record PartitionPayload(BigDecimal height, BigDecimal width, BigDecimal depth, Faces faces) {
        public record Faces(boolean left, boolean right, boolean end, boolean top) {}
    }

    public record LinearPayload(BigDecimal height, BigDecimal width, Sides sides, Integer qty) {
        public record Sides(boolean left, boolean right, boolean top, boolean bottom) {}
    }
}
