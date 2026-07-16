package com.majstr.backend.service.measurement;

import com.majstr.backend.exception.MeasurementException;

import java.util.Map;

/**
 * Surface-shape geometry — the Java mirror of the PWA's {@code src/lib/shapes.ts}.
 *
 * <p>Area is always the shoelace formula over built vertices, never a per-shape formula:
 * one code path covers every shape, including the skewed ones.
 *
 * <p>This lives on the server because the client never sets a measurement {@code result}.
 * A forged payload must not be able to invent an area, since that area is substituted
 * into an estimate line's quantity — i.e. into money. Both sides compute in {@code double}
 * so the front and the back agree to the last rounded digit.
 *
 * <p><b>Keep in lockstep with shapes.ts</b> — same vertices, same ok-conditions.
 */
final class Shapes {

    private Shapes() {
    }

    /** Area in the entered unit squared. Dimensions that don't form the figure are rejected. */
    static double area(String shape, String mode, Map<String, Double> values) {
        String m = mode == null ? "d" : mode;
        return switch (shape == null ? "" : shape) {
            case "rect" -> rect(values);
            case "trap" -> trap(values);
            case "attic" -> "asym".equals(m) ? atticAsym(values) : atticSym(values);
            case "tri" -> "sss".equals(m) ? triSss(values) : triBh(values);
            case "cut" -> cut(values);
            default -> throw new MeasurementException("error.measurement.invalid");
        };
    }

    // ---- shapes ---------------------------------------------------------------

    private static double rect(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        require(a > 0 && b > 0);
        return shoelace(new double[][]{{0, 0}, {a, 0}, {a, b}, {0, b}});
    }

    private static double trap(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        double h = v(vals, "h");
        require(a > 0 && b > 0 && h > 0);
        return shoelace(new double[][]{{0, 0}, {b, 0}, {(b + a) / 2, h}, {(b - a) / 2, h}});
    }

    private static double atticSym(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        double h = v(vals, "h");
        require(a > 0 && b > 0 && h >= b);
        return shoelace(new double[][]{{0, 0}, {a, 0}, {a, b}, {a / 2, h}, {0, b}});
    }

    private static double atticAsym(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        double c = v(vals, "c");
        double h = v(vals, "h");
        require(a > 0 && b > 0 && c > 0 && h >= Math.max(b, c));
        return shoelace(new double[][]{{0, 0}, {a, 0}, {a, c}, {a / 2, h}, {0, b}});
    }

    private static double triBh(Map<String, Double> vals) {
        double b = v(vals, "b");
        double h = v(vals, "h");
        require(b > 0 && h > 0);
        return shoelace(new double[][]{{0, 0}, {b, 0}, {b / 2, h}});
    }

    /** Three sides — the apex is placed from the side lengths, then shoelaced (Heron). */
    private static double triSss(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        double c = v(vals, "c");
        require(a > 0 && b > 0 && c > 0 && a + b > c && a + c > b && b + c > a);
        double x = (b * b - a * a + c * c) / (2 * c);
        double y = Math.sqrt(Math.max(0, b * b - x * x));
        return shoelace(new double[][]{{0, 0}, {c, 0}, {x, y}});
    }

    private static double cut(Map<String, Double> vals) {
        double a = v(vals, "a");
        double b = v(vals, "b");
        double c = v(vals, "c");
        double d = v(vals, "d");
        require(a > 0 && b > 0 && c > 0 && d > 0 && c <= a && d <= b);
        return shoelace(new double[][]{{0, 0}, {a, 0}, {a, d}, {c, b}, {0, b}});
    }

    // ---- helpers --------------------------------------------------------------

    /** |Σ(xᵢ·yᵢ₊₁ − xᵢ₊₁·yᵢ)| / 2 — area of any simple polygon from its vertices. */
    static double shoelace(double[][] pts) {
        double s = 0;
        for (int i = 0; i < pts.length; i++) {
            double[] p1 = pts[i];
            double[] p2 = pts[(i + 1) % pts.length];
            s += p1[0] * p2[1] - p2[0] * p1[1];
        }
        return Math.abs(s) / 2;
    }

    private static double v(Map<String, Double> vals, String key) {
        Double d = vals == null ? null : vals.get(key);
        return d == null ? 0 : d;
    }

    private static void require(boolean ok) {
        if (!ok) {
            throw new MeasurementException("error.measurement.invalid");
        }
    }
}
