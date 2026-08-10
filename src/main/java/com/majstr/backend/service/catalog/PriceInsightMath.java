package com.majstr.backend.service.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The cross-master half of the community-price two-level median (see {@code PriceInsightService}
 * for the per-master half, which happens in SQL). Pure functions, no Spring, so the trim/median
 * logic can be pinned by tests without a database.
 */
public final class PriceInsightMath {

    /** Below this many masters (after outlier trim), a position is not shown at all — three
     *  people independently agreeing is the minimum that isn't just noise. */
    public static final int MIN_MASTERS = 3;

    private static final BigDecimal IQR_MULTIPLIER = BigDecimal.valueOf(1.5);

    private PriceInsightMath() {
    }

    /** One trimmed, agreed-upon price for a position, or how spread out the survivors still are. */
    public record TrimmedStats(BigDecimal median, BigDecimal min, BigDecimal max, int count) {
    }

    /**
     * IQR-trims outliers out of the per-master values, then takes the median of what remains.
     * Returns {@code null} when fewer than {@link #MIN_MASTERS} values survive the trim — the
     * caller's signal to drop the whole candidate, not to show a number nobody actually agreed on.
     *
     * <p>Trimming runs even when there are exactly {@code MIN_MASTERS} values: a wild outlier
     * among exactly three masters must not single-handedly drag the "median" to itself the way a
     * plain median of 3 values would (the middle of {@code [500, 520, 999999]} is 520, which is
     * fine — but the middle of {@code [500, 999999]} after a naive 2-of-3 trim would not be).
     */
    public static TrimmedStats trimmedMedian(List<BigDecimal> perMasterValues) {
        if (perMasterValues.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = perMasterValues.stream().sorted().toList();
        BigDecimal q1 = percentile(sorted, 0.25);
        BigDecimal q3 = percentile(sorted, 0.75);
        BigDecimal iqr = q3.subtract(q1);
        BigDecimal lower = q1.subtract(iqr.multiply(IQR_MULTIPLIER));
        BigDecimal upper = q3.add(iqr.multiply(IQR_MULTIPLIER));

        List<BigDecimal> trimmed = sorted.stream()
                .filter(v -> v.compareTo(lower) >= 0 && v.compareTo(upper) <= 0)
                .toList();
        if (trimmed.size() < MIN_MASTERS) {
            return null;
        }
        BigDecimal median = percentile(trimmed, 0.5).setScale(2, RoundingMode.HALF_UP);
        return new TrimmedStats(median, trimmed.get(0), trimmed.get(trimmed.size() - 1), trimmed.size());
    }

    /** Plain median (no trimming) — used to fold one master's own multiple spelling-variant
     *  medians down to a single value before the cross-master step above ever sees them. */
    public static BigDecimal median(List<BigDecimal> values) {
        return percentile(values.stream().sorted().toList(), 0.5);
    }

    /**
     * Linear-interpolation percentile over an ALREADY-SORTED list — the same method Postgres'
     * {@code percentile_cont} uses, deliberately, so the per-master step (SQL) and this
     * cross-master step (Java) agree on what "median" means.
     */
    static BigDecimal percentile(List<BigDecimal> sorted, double p) {
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double rank = p * (n - 1);
        int lowIdx = (int) Math.floor(rank);
        int highIdx = (int) Math.ceil(rank);
        if (lowIdx == highIdx) {
            return sorted.get(lowIdx);
        }
        BigDecimal low = sorted.get(lowIdx);
        BigDecimal high = sorted.get(highIdx);
        BigDecimal fraction = BigDecimal.valueOf(rank - lowIdx);
        return low.add(high.subtract(low).multiply(fraction));
    }
}
