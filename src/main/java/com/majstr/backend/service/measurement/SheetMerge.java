package com.majstr.backend.service.measurement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Folds several readings of the SAME sheet into one — the whole page, then its fragments.
 *
 * <p>Pure map-to-map functions on the schema shape, deliberately: this is the step where a bug
 * silently produces a plausible-looking wrong wall, and it has to be testable without spending a
 * model call. Every rule here answers one question — given two readings of the same figure, which
 * one survives?</p>
 *
 * <ul>
 *   <li><b>The whole-page pass owns the table.</b> Room numbers, names and areas are set at a
 *       comfortable size and read reliably; a fragment sees only part of that table, or none.</li>
 *   <li><b>Fragments own the geometry.</b> That is the whole reason they exist — the dimension
 *       chains are legible there and were not on the downscaled full page.</li>
 *   <li><b>Disagreement is never resolved silently.</b> When both readings produce a figure and they
 *       differ by more than 2 %, the whole-page value stays AND the field is marked uncertain, so
 *       the master gets a number plus a reason to check it. Picking a winner by rule would be
 *       guessing with extra steps.</li>
 *   <li><b>A room only a fragment saw is kept, not dropped</b> — with a warning naming it. The
 *       opposite (dropping it) is how a room disappears from an estimate without anyone noticing.
 *       </li>
 * </ul>
 */
final class SheetMerge {

    /** Figures a fragment may contribute, in mm — everything geometric, nothing textual. */
    private static final List<String> GEOMETRY =
            List.of("widthMm", "lengthMm", "cutWidthMm", "cutDepthMm", "perimeterMm", "ceilingHmm");
    /** Above this relative gap, two readings are not one figure misrounded — one of them is wrong. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");

    private SheetMerge() {
    }

    /**
     * @param base      the whole-page reading — owns rooms, names and areas
     * @param fragments per-fragment readings in any order; each holds a subset of the rooms
     * @return a new root map in the same schema shape (inputs are not modified)
     */
    static Map<String, Object> mergeGeometry(Map<String, Object> base,
                                             List<Map<String, Object>> fragments) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        List<Map<String, Object>> floors = copyFloors(out.get("floors"));
        List<String> warnings = strings(out.get("warnings"));

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> floor : floors) {
            for (Object ro : list(floor.get("rooms"))) {
                if (ro instanceof Map<?, ?> rm) {
                    @SuppressWarnings("unchecked") Map<String, Object> room = (Map<String, Object>) rm;
                    byKey.putIfAbsent(roomKey(room), room);
                }
            }
        }

        for (Map<String, Object> fragment : fragments == null ? List.<Map<String, Object>>of() : fragments) {
            if (fragment == null) continue;
            for (String w : strings(fragment.get("warnings"))) {
                if (!warnings.contains(w)) warnings.add(w);
            }
            for (Object fo : list(fragment.get("floors"))) {
                if (!(fo instanceof Map<?, ?> fm)) continue;
                for (Object ro : list(fm.get("rooms"))) {
                    if (!(ro instanceof Map<?, ?> rm)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> incoming = (Map<String, Object>) rm;
                    String key = roomKey(incoming);
                    if (key.isEmpty()) continue; // a room with neither number nor name anchors nothing
                    Map<String, Object> target = byKey.get(key);
                    if (target == null) {
                        Map<String, Object> added = copyRoom(incoming);
                        appendNote(added, "знайдено лише на фрагменті аркуша");
                        String label = added.get("number") == null || added.get("number").toString().isBlank()
                                ? String.valueOf(added.get("name"))
                                : "№" + added.get("number");
                        String warn = "Кімнату " + label + " видно лише на фрагменті аркуша — перевірте";
                        if (!warnings.contains(warn)) warnings.add(warn);
                        targetFloor(floors, fm).add(added);
                        byKey.put(key, added);
                        continue;
                    }
                    foldRoom(target, incoming);
                }
            }
            mergeCeilingHeights(out, fragment);
            mergeCoverings(out, fragment);
            mergeTotals(out, fragment);
            if (blank(out.get("sheetTitle")) && !blank(fragment.get("sheetTitle"))) {
                out.put("sheetTitle", fragment.get("sheetTitle"));
            }
        }

        out.put("floors", floors);
        out.put("warnings", warnings);
        return out;
    }

    // ---- one room ---------------------------------------------------------------

    private static void foldRoom(Map<String, Object> target, Map<String, Object> incoming) {
        for (String field : GEOMETRY) {
            BigDecimal mine = positive(target.get(field));
            BigDecimal theirs = positive(incoming.get(field));
            if (theirs == null) continue;
            if (mine == null) {
                // The point of the whole exercise: a figure the full page could not resolve.
                target.put(field, theirs);
                continue;
            }
            if (disagrees(mine, theirs)) {
                markUncertain(target, field);
                appendNote(target, "фрагмент дає " + plain(theirs) + " замість " + plain(mine)
                        + " — перепровірте " + field);
            }
        }
        if (positive(target.get("areaM2")) == null && positive(incoming.get("areaM2")) != null) {
            target.put("areaM2", incoming.get("areaM2"));
            unmarkUncertain(target, "areaM2");
        }
        if (numbers(target.get("wallSegmentsMm")).isEmpty()) {
            List<BigDecimal> segments = numbers(incoming.get("wallSegmentsMm"));
            if (!segments.isEmpty()) target.put("wallSegmentsMm", new ArrayList<Object>(segments));
        }
        mergeOpenings(target, incoming);
        for (String u : strings(incoming.get("uncertain"))) {
            markUncertain(target, u);
        }
        if (blank(target.get("note")) && !blank(incoming.get("note"))) {
            target.put("note", incoming.get("note"));
        }
    }

    /**
     * Openings are UNIONED, not replaced, and de-duplicated on kind+width+height. Fragments overlap
     * by design, so the same window legitimately arrives twice; and one room's openings are often
     * split across two fragments, so keeping only one side would quietly remove a window from the
     * wall area.
     */
    private static void mergeOpenings(Map<String, Object> target, Map<String, Object> incoming) {
        List<Object> mine = list(target.get("openings"));
        List<String> seen = new ArrayList<>();
        for (Object o : mine) {
            if (o instanceof Map<?, ?> om) seen.add(openingKey(om));
        }
        for (Object o : list(incoming.get("openings"))) {
            if (!(o instanceof Map<?, ?> om)) continue;
            String key = openingKey(om);
            if (seen.contains(key)) continue;
            seen.add(key);
            mine.add(new LinkedHashMap<>(om));
        }
        target.put("openings", mine);
    }

    private static String openingKey(Map<?, ?> o) {
        return String.valueOf(o.get("kind")) + '|' + plain(positive(o.get("wMm")))
                + '|' + plain(positive(o.get("hMm")));
    }

    // ---- root-level collections --------------------------------------------------

    private static void mergeCeilingHeights(Map<String, Object> out, Map<String, Object> fragment) {
        List<Object> heights = list(out.get("ceilingHeights"));
        List<String> floorsSeen = new ArrayList<>();
        for (Object h : heights) {
            if (h instanceof Map<?, ?> hm) floorsSeen.add(String.valueOf(hm.get("floor")));
        }
        for (Object h : list(fragment.get("ceilingHeights"))) {
            if (!(h instanceof Map<?, ?> hm) || positive(hm.get("heightMm")) == null) continue;
            if (floorsSeen.contains(String.valueOf(hm.get("floor")))) continue;
            floorsSeen.add(String.valueOf(hm.get("floor")));
            heights.add(new LinkedHashMap<>(hm));
        }
        out.put("ceilingHeights", heights);
    }

    private static void mergeCoverings(Map<String, Object> out, Map<String, Object> fragment) {
        List<Object> coverings = list(out.get("coverings"));
        List<String> seen = new ArrayList<>();
        for (Object c : coverings) {
            if (c instanceof Map<?, ?> cm) seen.add(coveringKey(cm));
        }
        for (Object c : list(fragment.get("coverings"))) {
            if (!(c instanceof Map<?, ?> cm) || positive(cm.get("qty")) == null) continue;
            String key = coveringKey(cm);
            if (seen.contains(key)) continue;
            seen.add(key);
            coverings.add(new LinkedHashMap<>(cm));
        }
        out.put("coverings", coverings);
    }

    private static String coveringKey(Map<?, ?> c) {
        return String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT)
                + '|' + plain(positive(c.get("qty"))) + '|' + c.get("unit");
    }

    /** «Загальна площа» is a footer figure: whoever read one wins, and the full page reads first. */
    @SuppressWarnings("unchecked")
    private static void mergeTotals(Map<String, Object> out, Map<String, Object> fragment) {
        BigDecimal mine = out.get("totals") instanceof Map<?, ?> tm
                ? positive(((Map<String, Object>) tm).get("totalAreaM2")) : null;
        if (mine != null) return;
        if (!(fragment.get("totals") instanceof Map<?, ?> ftm)) return;
        BigDecimal theirs = positive(((Map<String, Object>) ftm).get("totalAreaM2"));
        if (theirs == null) return;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalAreaM2", theirs);
        out.put("totals", totals);
    }

    // ---- small helpers -----------------------------------------------------------

    /** The floor list a fragment's room belongs to, matched by label; the first floor otherwise. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> targetFloor(List<Map<String, Object>> floors,
                                                         Map<?, ?> fragmentFloor) {
        String label = fragmentFloor.get("floor") == null ? "" : fragmentFloor.get("floor").toString();
        Map<String, Object> chosen = null;
        for (Map<String, Object> f : floors) {
            String mine = f.get("floor") == null ? "" : f.get("floor").toString();
            if (mine.equals(label)) {
                chosen = f;
                break;
            }
        }
        if (chosen == null && !floors.isEmpty()) chosen = floors.get(0);
        if (chosen == null) {
            chosen = new LinkedHashMap<>();
            chosen.put("floor", label);
            chosen.put("roomsOnThisSheet", new ArrayList<>());
            chosen.put("rooms", new ArrayList<>());
            floors.add(chosen);
        }
        Object rooms = chosen.get("rooms");
        if (!(rooms instanceof List)) {
            chosen.put("rooms", new ArrayList<>());
        }
        return (List<Map<String, Object>>) (List<?>) chosen.get("rooms");
    }

    private static List<Map<String, Object>> copyFloors(Object floors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object fo : list(floors)) {
            if (!(fo instanceof Map<?, ?> fm)) continue;
            Map<String, Object> floor = new LinkedHashMap<>();
            floor.put("floor", fm.get("floor"));
            floor.put("roomsOnThisSheet", new ArrayList<>(list(fm.get("roomsOnThisSheet"))));
            List<Map<String, Object>> rooms = new ArrayList<>();
            for (Object ro : list(fm.get("rooms"))) {
                if (ro instanceof Map<?, ?> rm) {
                    @SuppressWarnings("unchecked") Map<String, Object> room = (Map<String, Object>) rm;
                    rooms.add(copyRoom(room));
                }
            }
            floor.put("rooms", rooms);
            out.add(floor);
        }
        return out;
    }

    private static Map<String, Object> copyRoom(Map<String, Object> room) {
        Map<String, Object> copy = new LinkedHashMap<>(room);
        List<Object> openings = new ArrayList<>();
        for (Object o : list(room.get("openings"))) {
            if (o instanceof Map<?, ?> om) openings.add(new LinkedHashMap<>(om));
        }
        copy.put("openings", openings);
        copy.put("uncertain", new ArrayList<Object>(strings(room.get("uncertain"))));
        copy.put("wallSegmentsMm", new ArrayList<>(list(room.get("wallSegmentsMm"))));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void markUncertain(Map<String, Object> room, String field) {
        if (field == null || field.isBlank()) return;
        Object current = room.get("uncertain");
        List<Object> fields = current instanceof List ? (List<Object>) current : new ArrayList<>();
        if (!fields.contains(field)) fields.add(field);
        room.put("uncertain", fields);
    }

    private static void unmarkUncertain(Map<String, Object> room, String field) {
        Object current = room.get("uncertain");
        if (current instanceof List<?> l) {
            List<Object> kept = new ArrayList<>();
            for (Object o : l) {
                if (!field.equals(o)) kept.add(o);
            }
            room.put("uncertain", kept);
        }
    }

    private static void appendNote(Map<String, Object> room, String text) {
        String existing = blank(room.get("note")) ? "" : room.get("note").toString().trim();
        if (existing.contains(text)) return;
        room.put("note", existing.isEmpty() ? text : existing + "; " + text);
    }

    static String roomKey(Map<String, Object> room) {
        Object number = room.get("number");
        String n = number == null ? "" : number.toString().trim();
        if (!n.isEmpty()) return "#" + n;
        Object name = room.get("name");
        return name == null ? "" : name.toString().trim().toLowerCase(Locale.ROOT);
    }

    static BigDecimal positive(Object o) {
        BigDecimal v = null;
        if (o instanceof Number n) {
            v = new BigDecimal(n.toString());
        } else if (o instanceof String s && !s.isBlank()) {
            try {
                v = new BigDecimal(s.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return v != null && v.signum() > 0 ? v : null;
    }

    /** True when two readings of one figure are far enough apart that one of them is a misread. */
    static boolean disagrees(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        BigDecimal bigger = a.abs().max(b.abs());
        if (bigger.signum() == 0) return false;
        return a.subtract(b).abs().divide(bigger, 4, RoundingMode.HALF_UP).compareTo(TOLERANCE) > 0;
    }

    private static List<BigDecimal> numbers(Object o) {
        List<BigDecimal> out = new ArrayList<>();
        for (Object v : list(o)) {
            BigDecimal d = positive(v);
            if (d != null) out.add(d);
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object v : list(o)) {
            if (v != null && !v.toString().isBlank() && !out.contains(v.toString())) {
                out.add(v.toString());
            }
        }
        return out;
    }

    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
    }

    private static boolean blank(Object o) {
        return o == null || o.toString().isBlank();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
