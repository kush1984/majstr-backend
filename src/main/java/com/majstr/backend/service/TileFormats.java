package com.majstr.backend.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tile format a line's NAME states — «Укладання плитки 300х600», «… 1200x200 ялинкою». Read to
 * decide whether a stored layout still describes the line (iteration-tile-layout): renamed to another
 * format, the layout is for a different tile and is paused, never silently re-applied.
 */
final class TileFormats {

    /** Two numbers joined by a Latin x, a Cyrillic х, × or * — how masters and the library write it. */
    private static final Pattern FORMAT = Pattern.compile("(\\d{2,4})\\s*[xXхХ×*]\\s*(\\d{2,4})");

    /** Short side first, millimetres as written. */
    record Format(int shortMm, int longMm) {
        static Format of(int a, int b) {
            return new Format(Math.min(a, b), Math.max(a, b));
        }

        /** «300×500 мм» — the custom format's own label on the buying list. */
        String label() {
            return shortMm + "×" + longMm + " мм";
        }
    }

    private TileFormats() {
    }

    static Optional<Format> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(name);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(Format.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    }

    /** A laying job in the master's own words: «Укладання плитки на підлогу 60×60», «Облицювання стін керамогранітом 30×60». */
    private static final Pattern LAYING = Pattern.compile(
            "(?U)^(укладання|облицювання|лицювання|викладання|монтаж)\\b.*(плитк|керамограніт|кахл)");
    /** Not the indoor ceramic tile the library formats are: paving, clinker, mosaic, natural stone. */
    private static final Pattern OTHER_TILE = Pattern.compile("тротуарн|клінкер|мозаїк|бруківк|камен|камін");
    /** «до 60×60», «від 30×60» — a RANGE of formats, so no one format to stand for it. */
    private static final Pattern RANGE = Pattern.compile("(?U)\\b(до|від|понад|більше|менше)\\s*\\d");

    /**
     * The library laying positions a tiling line in the master's OWN words stands for (iteration-tile-layout,
     * option A): one clear format in the name → «укладання плитки 600х600» (and the « мм» spelling the large
     * formats are shipped under), whose norms then answer for the line. Anything unclear answers nothing — a
     * range («до 60×60»), two formats, no format, a demolition or a cut, paving or clinker — so the line is
     * left uncounted rather than given someone else's materials. Centimetres (60×60, 120×60) read as mm × 10.
     */
    static java.util.List<String> libraryKeysFor(String name) {
        String key = NameKeys.of(name);
        if (!LAYING.matcher(key).find() || OTHER_TILE.matcher(key).find() || RANGE.matcher(key).find()) {
            return java.util.List.of();
        }
        Matcher m = FORMAT.matcher(key);
        if (!m.find()) {
            return java.util.List.of();
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        if (m.find()) {
            return java.util.List.of(); // two formats in one name
        }
        // No library tile is under 100 mm a side (mosaic is excluded above), so a side under 100 means the
        // master wrote centimetres: 60×60, 30×60, and 120×60.
        if (Math.min(a, b) < 100) {
            a *= 10;
            b *= 10;
        }
        Format f = Format.of(a, b);
        String base = "укладання плитки " + f.shortMm() + "х" + f.longMm();
        return java.util.List.of(base, base + " мм");
    }

    /**
     * Does the name still describe a layout made for {@code layout}? A name with no format says
     * nothing against it; «30х60» is the same tile in centimetres.
     */
    static boolean nameAgrees(String name, Format layout) {
        return fromName(name)
                .map(f -> f.equals(layout) || Format.of(f.shortMm() * 10, f.longMm() * 10).equals(layout))
                .orElse(true);
    }
}
