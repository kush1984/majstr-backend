package com.majstr.backend.service.measurement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folding fragment readings into the whole-page reading.
 *
 * <p>Worth testing without a model because every rule here is a decision about which of two numbers
 * ends up in the master's estimate, and a wrong one is invisible: it produces a plausible wall area
 * that is simply not the room he is standing in.</p>
 */
class SheetMergeTest {

    // ---- helpers to keep the schema shape readable ------------------------------

    private static Map<String, Object> room(String number, String name, Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("number", number);
        r.put("name", name);
        r.put("areaM2", 0);
        r.put("widthMm", 0);
        r.put("lengthMm", 0);
        r.put("ceilingHmm", 0);
        r.put("perimeterMm", 0);
        r.put("wallSegmentsMm", new ArrayList<>());
        r.put("openings", new ArrayList<>());
        r.put("uncertain", new ArrayList<>());
        r.put("note", "");
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private static Map<String, Object> sheet(String title, List<Map<String, Object>> rooms, Object... kv) {
        Map<String, Object> floor = new LinkedHashMap<>();
        floor.put("floor", "");
        floor.put("roomsOnThisSheet", new ArrayList<>());
        floor.put("rooms", new ArrayList<>(rooms));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sheetTitle", title);
        root.put("floors", new ArrayList<>(List.of(floor)));
        root.put("coverings", new ArrayList<>());
        root.put("ceilingHeights", new ArrayList<>());
        root.put("warnings", new ArrayList<>());
        root.put("totals", new LinkedHashMap<>(Map.of("totalAreaM2", 0)));
        for (int i = 0; i < kv.length; i += 2) {
            root.put((String) kv[i], kv[i + 1]);
        }
        return root;
    }

    /** {@code List<?>} is a capture type, so AssertJ's contains(String) does not apply to it. */
    private static List<Object> objs(Object o) {
        return new ArrayList<>((List<?>) o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRoom(Map<String, Object> root) {
        Map<String, Object> floor = ((List<Map<String, Object>>) root.get("floors")).get(0);
        return ((List<Map<String, Object>>) floor.get("rooms")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rooms(Map<String, Object> root) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) root.get("floors")) {
            all.addAll((List<Map<String, Object>>) f.get("rooms"));
        }
        return all;
    }

    // ---- the point of the whole exercise ---------------------------------------

    @Test
    void aFragmentFillsGeometryTheWholePageCouldNotRead() {
        // The reported failure: the table reads (name + area), every chain comes back 0. The
        // fragment is the only reading that ever produces those chains.
        Map<String, Object> base = sheet("ОБМІРНИЙ ПЛАН",
                List.of(room("4", "Дитяча", "areaM2", 16.46)));
        Map<String, Object> fragment = sheet("",
                List.of(room("4", "Дитяча", "widthMm", 4730, "lengthMm", 3480, "ceilingHmm", 2850)));

        Map<String, Object> merged = SheetMerge.mergeGeometry(base, List.of(fragment));

        Map<String, Object> r = firstRoom(merged);
        assertThat(SheetMerge.positive(r.get("widthMm"))).isEqualByComparingTo("4730");
        assertThat(SheetMerge.positive(r.get("lengthMm"))).isEqualByComparingTo("3480");
        assertThat(SheetMerge.positive(r.get("ceilingHmm"))).isEqualByComparingTo("2850");
        // The table's own figures are never overwritten by a fragment that saw part of the sheet.
        assertThat(SheetMerge.positive(r.get("areaM2"))).isEqualByComparingTo("16.46");
        assertThat(r.get("name")).isEqualTo("Дитяча");
    }

    @Test
    void twoReadingsThatDisagreeKeepTheFullPageValueAndSaySo() {
        // Never silently pick a winner: the master gets a number AND a reason to check it.
        Map<String, Object> base = sheet("", List.of(room("2", "Спальня", "widthMm", 3545)));
        Map<String, Object> fragment = sheet("", List.of(room("2", "Спальня", "widthMm", 5000)));

        Map<String, Object> r = firstRoom(SheetMerge.mergeGeometry(base, List.of(fragment)));

        assertThat(SheetMerge.positive(r.get("widthMm"))).isEqualByComparingTo("3545");
        assertThat(objs(r.get("uncertain"))).contains("widthMm");
        assertThat(r.get("note").toString()).contains("5000").contains("widthMm");
        // The guard on the fragment-vs-fragment rule below: a whole-page reading is NOT a crop —
        // that pass saw the entire room — so a bigger fragment figure is a misread, not more room.
        assertThat(r.get("note").toString()).doesNotContain("узято більше");
    }

    @Test
    void anLshapedCorridorSplitAcrossTWOfragmentsKeepsItsBOUNDINGbox() {
        // The reported failure on Дубляни: the Г-shaped corridor came back with no geometry at all.
        // It is the room most likely to straddle a seam — it wraps a corner — so each fragment sees
        // one arm. Keeping the first arrival stored ONE ARM as the gabarits, which is smaller than
        // the printed area, and the client rejects a room whose width × length is under its area.
        // Both readings here are crops, and a crop cannot see more of the room than exists, so the
        // larger one saw more of it.
        Map<String, Object> base = sheet("ОБМІРНИЙ ПЛАН",
                List.of(room("6", "Коридор", "areaM2", 7.56)));
        Map<String, Object> armOne = sheet("",
                List.of(room("6", "Коридор", "widthMm", 1200, "lengthMm", 4000)));
        Map<String, Object> armTwo = sheet("",
                List.of(room("6", "Коридор", "widthMm", 3500, "lengthMm", 1100)));

        Map<String, Object> r = firstRoom(SheetMerge.mergeGeometry(base, List.of(armOne, armTwo)));

        assertThat(SheetMerge.positive(r.get("widthMm"))).isEqualByComparingTo("3500");
        assertThat(SheetMerge.positive(r.get("lengthMm"))).isEqualByComparingTo("4000");
        // Never silently: the master is still told the two fragments disagreed.
        assertThat(objs(r.get("uncertain"))).contains("widthMm", "lengthMm");
        assertThat(r.get("note").toString()).contains("узято більше");
    }

    @Test
    void aRoundingLevelDifferenceIsNotADisagreement() {
        // 2850 vs 2860 is one figure read twice, not a misread — flagging it would train the
        // master to ignore the flag, which is worse than not having one.
        Map<String, Object> base = sheet("", List.of(room("1", "Передпокій", "ceilingHmm", 2850)));
        Map<String, Object> fragment = sheet("", List.of(room("1", "Передпокій", "ceilingHmm", 2860)));

        Map<String, Object> r = firstRoom(SheetMerge.mergeGeometry(base, List.of(fragment)));

        assertThat(SheetMerge.positive(r.get("ceilingHmm"))).isEqualByComparingTo("2850");
        assertThat((List<?>) r.get("uncertain")).isEmpty();
    }

    @Test
    void openingsAreUnionedAcrossFragmentsAndDeduplicated() {
        // A room's wall can be split between two fragments, and the overlap means the same window
        // legitimately arrives twice. Replacing instead of unioning removes a window from the wall
        // area; not deduplicating subtracts it twice.
        Map<String, Object> window = new LinkedHashMap<>(Map.of(
                "kind", "вікно", "wMm", 1500, "hMm", 1500, "sillMm", 900, "toFloor", false, "note", ""));
        Map<String, Object> door = new LinkedHashMap<>(Map.of(
                "kind", "двері", "wMm", 900, "hMm", 2200, "sillMm", 0, "toFloor", true, "note", ""));
        Map<String, Object> base = sheet("", List.of(
                room("7", "Коридор", "openings", new ArrayList<>(List.of(window)))));
        Map<String, Object> left = sheet("", List.of(
                room("7", "Коридор", "openings", new ArrayList<>(List.of(window)))));
        Map<String, Object> right = sheet("", List.of(
                room("7", "Коридор", "openings", new ArrayList<>(List.of(door)))));

        Map<String, Object> r = firstRoom(SheetMerge.mergeGeometry(base, List.of(left, right)));

        assertThat((List<?>) r.get("openings")).hasSize(2);
    }

    @Test
    void aRoomOnlyAFragmentSawIsKeptWithAWarning() {
        Map<String, Object> base = sheet("", List.of(room("1", "Передпокій", "areaM2", 7.16)));
        Map<String, Object> fragment = sheet("", List.of(room("9", "Котельня", "areaM2", 5.39)));

        Map<String, Object> merged = SheetMerge.mergeGeometry(base, List.of(fragment));

        assertThat(rooms(merged)).hasSize(2);
        assertThat(rooms(merged).get(1).get("note").toString()).contains("фрагменті");
        assertThat((List<?>) merged.get("warnings")).anyMatch(w -> w.toString().contains("№9"));
    }

    @Test
    void aFragmentSuppliesTheAreaWhenTheSheetPrintedNoTable() {
        // exampleUa's measure plan has 71 dimension chains and not one area. Whoever reads an area
        // first wins, and the field stops being flagged once a real figure exists.
        Map<String, Object> base = sheet("02_обмірний план", List.of(
                room("3", "Кухня", "uncertain", new ArrayList<>(List.of("areaM2")))));
        Map<String, Object> fragment = sheet("", List.of(room("3", "Кухня", "areaM2", 13.04)));

        Map<String, Object> r = firstRoom(SheetMerge.mergeGeometry(base, List.of(fragment)));

        assertThat(SheetMerge.positive(r.get("areaM2"))).isEqualByComparingTo("13.04");
        assertThat(objs(r.get("uncertain"))).doesNotContain("areaM2");
    }

    @Test
    void theFullPageKeepsItsSheetTitleAndTotalsAndCollectsWhatItLacked() {
        Map<String, Object> base = sheet("ОБМІРНИЙ ПЛАН", List.of(room("1", "Передпокій")));
        Map<String, Object> fragment = sheet("фрагмент", List.of(room("1", "Передпокій")),
                "totals", new LinkedHashMap<>(Map.of("totalAreaM2", 163.91)),
                "ceilingHeights", new ArrayList<>(List.of(
                        new LinkedHashMap<>(Map.of("floor", "1", "heightMm", 2850)))),
                "warnings", new ArrayList<>(List.of("Всі розміри уточнити на місці")));

        Map<String, Object> merged = SheetMerge.mergeGeometry(base, List.of(fragment));

        assertThat(merged.get("sheetTitle")).isEqualTo("ОБМІРНИЙ ПЛАН");
        assertThat(((Map<?, ?>) merged.get("totals")).get("totalAreaM2")).isNotNull();
        assertThat((List<?>) merged.get("ceilingHeights")).hasSize(1);
        assertThat(objs(merged.get("warnings"))).contains("Всі розміри уточнити на місці");
    }

    @Test
    void inputsAreNotMutatedAndAnEmptyFragmentListIsHarmless() {
        Map<String, Object> base = sheet("", List.of(room("1", "Передпокій", "widthMm", 1000)));

        Map<String, Object> merged = SheetMerge.mergeGeometry(base, List.of());
        firstRoom(merged).put("widthMm", 9999);

        assertThat(SheetMerge.positive(firstRoom(base).get("widthMm"))).isEqualByComparingTo("1000");
        assertThat(SheetMerge.mergeGeometry(base, null)).isNotNull();
    }

    @Test
    void aNumberArrivingAsAStringWithACommaStillCounts() {
        // Both separators appear in real sets, and a model handed «12,63» may echo it as a string.
        assertThat(SheetMerge.positive("12,63")).isEqualByComparingTo("12.63");
        assertThat(SheetMerge.positive("0")).isNull();
        assertThat(SheetMerge.positive("не вказано")).isNull();
    }

    @Test
    void disagreementMeansTheWRONGCHAIN_notADifferentRounding() {
        // Where the 2 % line sits, and why: a real misread takes a NEIGHBOURING chain (3545 vs the
        // 4990 next to it) or loses a thousands group. Two readings 1.5 % apart are the same figure,
        // and flagging those would bury the flags that matter under noise.
        assertThat(SheetMerge.disagrees(SheetMerge.positive("3545"), SheetMerge.positive("4990")))
                .isTrue();
        assertThat(SheetMerge.disagrees(SheetMerge.positive("5000"), SheetMerge.positive("5")))
                .isTrue();
        assertThat(SheetMerge.disagrees(SheetMerge.positive("3545"), SheetMerge.positive("3600")))
                .isFalse();
        // Nothing to compare against is not a disagreement — it is the fragment filling a hole.
        assertThat(SheetMerge.disagrees(null, SheetMerge.positive("3545"))).isFalse();
    }
}
