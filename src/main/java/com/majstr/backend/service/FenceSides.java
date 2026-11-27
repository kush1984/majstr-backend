package com.majstr.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A fence's sides as the master types them on the materials screen — «25 + 15 + 25; 10» (calculator
 * review 2026-11-27, V227). The one answer a run of whole panels needs and a line's м.п. cannot carry.
 *
 * <p>Two facts make the total length the wrong thing to divide. A panel does not bend round a
 * corner, so every SIDE starts a fresh panel and ends in a cut one: 25 m and 15 m are 10 + 6
 * panels, where 40 m as one figure is 16 only by luck (12,6 m and 12,6 m are 6 + 6, not 11). And a
 * post closes each continuous RUN: a run of N panels stands on N + 1 posts, a corner post being
 * shared — so a gate, which breaks the fence, costs one post more. Sides are separated by «+», a
 * space or a comma followed by a space; a run is closed by «;»; a comma between digits is a
 * decimal one («12,5»).</p>
 *
 * @param runs the continuous runs, each the lengths of its sides in metres, in order
 */
public record FenceSides(List<List<BigDecimal>> runs) {

    /** Well past any plot's side and short of a mistyped one; the same for the number of sides. */
    static final BigDecimal MAX_SIDE_M = new BigDecimal("1000");
    static final int MAX_SIDES = 50;
    public static final int MAX_TEXT = 300;

    private static final Pattern DECIMAL_COMMA = Pattern.compile("(?<=\\d),(?=\\d)");
    private static final Pattern SIDE_SEPARATOR = Pattern.compile("[+\\s,]+");

    /** The master's text read, or empty when any part of it is not a side we can count. */
    public static Optional<FenceSides> parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TEXT) {
            return Optional.empty();
        }
        List<List<BigDecimal>> runs = new ArrayList<>();
        int sides = 0;
        for (String run : DECIMAL_COMMA.matcher(raw.trim()).replaceAll(".").split(";")) {
            List<BigDecimal> lengths = new ArrayList<>();
            for (String token : SIDE_SEPARATOR.split(run.trim())) {
                if (token.isEmpty()) {
                    continue;
                }
                BigDecimal side;
                try {
                    side = new BigDecimal(token);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (side.signum() <= 0 || side.compareTo(MAX_SIDE_M) > 0 || ++sides > MAX_SIDES) {
                    return Optional.empty();
                }
                lengths.add(side);
            }
            if (!lengths.isEmpty()) {
                runs.add(List.copyOf(lengths));
            }
        }
        return runs.isEmpty() ? Optional.empty() : Optional.of(new FenceSides(List.copyOf(runs)));
    }

    /** Whole pieces of {@code module} metres along every side, each side's last one cut to fit. */
    public int pieces(BigDecimal module) {
        int pieces = 0;
        for (List<BigDecimal> run : runs) {
            for (BigDecimal side : run) {
                pieces += side.divide(module, 0, RoundingMode.CEILING).intValueExact();
            }
        }
        return pieces;
    }

    public BigDecimal total() {
        return runs.stream().flatMap(List::stream).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int runCount() {
        return runs.size();
    }

    /** What is stored and shown back: «25 + 15 + 25; 10», a decimal written with a comma. */
    public String canonical() {
        return runs.stream()
                .map(run -> run.stream()
                        .map(side -> side.stripTrailingZeros().toPlainString().replace('.', ','))
                        .collect(Collectors.joining(" + ")))
                .collect(Collectors.joining("; "));
    }
}
