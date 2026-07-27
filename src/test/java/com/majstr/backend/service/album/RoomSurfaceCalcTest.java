package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.Meta;
import com.majstr.backend.service.album.AlbumExtraction.Opening;
import com.majstr.backend.service.album.AlbumExtraction.PanelLocation;
import com.majstr.backend.service.album.AlbumExtraction.Room;
import com.majstr.backend.service.album.AlbumExtraction.Status;
import com.majstr.backend.service.album.RoomSurfaceCalc.Result;
import com.majstr.backend.service.album.RoomSurfaceCalc.RoomSurfaces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the surface arithmetic against hand-computed numbers (the Belgradska bedroom:
 * 4990×3545, H=2850, window 1300×1500, door 900×2200) and the honesty branches: missing
 * window heights, underivable perimeters, and floor-openings without width must surface
 * as notes/warnings — never as silently-wrong numbers.
 */
class RoomSurfaceCalcTest {

    private final RoomSurfaceCalc calc = new RoomSurfaceCalc();

    @Test
    void rectangularRoomFullTakeoff() {
        // P = 2×(4.99+3.545) = 17.07; стіни брутто = 17.07×2.85 = 48.65 (48.6495)
        // прорізи = 1.3×1.5 + 0.9×2.2 = 1.95+1.98 = 3.93; нетто = 44.72 (44.7195)
        // плінтус = 17.07 − 0.9 = 16.17
        // відкоси: вікно 2×1.5+1.3 = 4.3; двері 2×2.2+0.9 = 5.3 → 9.6; підвіконня 1.3
        Room bedroom = room("Спальня", "4990×3545", null, 17.69, 2850, null);
        Opening window = opening("Спальня", null, "window", 1300, 1500, false);
        Opening door = opening("Спальня", "Коридор", "door_interior", 900, 2200, true);

        Result result = calc.calculate(extraction(List.of(bedroom), List.of(window, door)));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.perimeterM()).isEqualByComparingTo("17.07");
        assertThat(r.floorAreaM2()).isEqualByComparingTo("17.69");
        assertThat(r.ceilingAreaM2()).isEqualByComparingTo("17.69");
        assertThat(r.wallsGrossM2()).isEqualByComparingTo("48.65");
        assertThat(r.openingsAreaM2()).isEqualByComparingTo("3.93");
        assertThat(r.wallsNetM2()).isEqualByComparingTo("44.72");
        assertThat(r.plinthM()).isEqualByComparingTo("16.17");
        assertThat(r.revealsM()).isEqualByComparingTo("9.60");
        assertThat(r.sillsM()).isEqualByComparingTo("1.30");
        assertThat(r.windowCount()).isEqualTo(1);
        assertThat(r.verify()).isFalse();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void complexRoomUsesPerimeterFromExtraction() {
        // Т-подібний коридор Дублян: perimeter_m = 21.22 (LLM рахує за контуром)
        // стіни брутто = 21.22 × 2.85 = 60.48 (60.477)
        Room corridor = room("Коридор", "5880×1830 + 1980×2900", 21.22, 16.51, 2850, null);

        Result result = calc.calculate(extraction(List.of(corridor), List.of()));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.perimeterM()).isEqualByComparingTo("21.22");
        assertThat(r.wallsGrossM2()).isEqualByComparingTo("60.48");
    }

    @Test
    void complexRoomWithoutPerimeterIsAnHonestGap() {
        Room tricky = room("Кухня-вітальня", "5000×5394 + 2980×4474 + еркер", null, 64.4, 2850, null);

        Result result = calc.calculate(extraction(List.of(tricky), List.of()));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.perimeterM()).isNull();
        assertThat(r.wallsGrossM2()).isNull();
        assertThat(r.wallsNetM2()).isNull();
        assertThat(r.plinthM()).isNull();
        assertThat(r.floorAreaM2()).isEqualByComparingTo("64.40"); // площа з експлікації лишається
        assertThat(result.warnings()).anyMatch(w -> w.contains("без периметра"));
    }

    @Test
    void openingWithoutHeightIsSkippedAndFlagged() {
        // Дублянський випадок: висот вікон в альбомі немає взагалі.
        Room room = room("Кабінет", "2770×4940", null, 13.7, 2930, null);
        Opening window = opening("Кабінет", null, "window", 1480, null, false);

        Result result = calc.calculate(extraction(List.of(room), List.of(window)));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.wallsGrossM2()).isNotNull();
        assertThat(r.openingsAreaM2()).isEqualByComparingTo("0.00"); // нічого не відняли
        assertThat(r.wallsNetM2()).isEqualByComparingTo(r.wallsGrossM2()); // нетто = брутто (чесно ЗАВИЩЕНО)
        assertThat(r.revealsM()).isEqualByComparingTo("0.00");
        assertThat(r.sillsM()).isEqualByComparingTo("1.48"); // ширина відома — підвіконня рахується
        assertThat(r.verify()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("ЗАВИЩЕНІ"));
    }

    @Test
    void sharedDoorIsDeductedFromBothRooms() {
        Room a = room("Спальня", "4000×3000", null, 12.0, 2800, null);
        Room b = room("Коридор", "5000×1500", null, 7.5, 2800, null);
        Opening door = opening("Спальня", "Коридор", "door_interior", 900, 2100, true);

        Result result = calc.calculate(extraction(List.of(a, b), List.of(door)));

        // 0.9×2.1 = 1.89 віднято з ОБОХ кімнат; плінтус обох мінус 0.9.
        assertThat(result.rooms().get(0).openingsAreaM2()).isEqualByComparingTo("1.89");
        assertThat(result.rooms().get(1).openingsAreaM2()).isEqualByComparingTo("1.89");
        assertThat(result.rooms().get(0).plinthM()).isEqualByComparingTo("13.10"); // 14 − 0.9
        assertThat(result.rooms().get(1).plinthM()).isEqualByComparingTo("12.10"); // 13 − 0.9
    }

    @Test
    void missingCeilingHeightLeavesWallsNull() {
        Room room = room("Гардероб", "2260×2780", null, 6.28, null, null);

        Result result = calc.calculate(extraction(List.of(room), List.of()));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.perimeterM()).isEqualByComparingTo("10.08");
        assertThat(r.wallsGrossM2()).isNull();
        assertThat(r.plinthM()).isEqualByComparingTo("10.08"); // плінтус від периметра не залежить від H
        assertThat(r.notes()).anyMatch(n -> n.contains("висота стелі"));
    }

    @Test
    void mansardSlopeForcesVerify() {
        Room mansard = room("Спальня 2п", "4847×5394", null, 30.0, 2800, "мансарда: скіс від 1550");

        Result result = calc.calculate(extraction(List.of(mansard), List.of()));

        assertThat(result.rooms().get(0).verify()).isTrue();
        assertThat(result.rooms().get(0).notes()).anyMatch(n -> n.contains("скоси"));
    }

    @Test
    void toFloorOpeningWithoutWidthFlagsPlinth() {
        Room hall = room("Передпокій", "2990×2395", null, 7.16, 2850, null);
        Opening entrance = opening("Передпокій", null, "door_entrance", null, 2064, true);

        Result result = calc.calculate(extraction(List.of(hall), List.of(entrance)));

        RoomSurfaces r = result.rooms().get(0);
        assertThat(r.plinthM()).isEqualByComparingTo("10.77"); // без відомої ширини — не віднімаємо
        assertThat(r.notes()).anyMatch(n -> n.contains("плінтус завищений"));
        assertThat(r.verify()).isTrue();
    }

    @Test
    void totalsSumOnlyComputedValues() {
        Room ok = room("Спальня", "4000×3000", null, 12.0, 2800, null);        // стіни 39.2
        Room noPerimeter = room("Вітальня", "складна форма", null, 40.0, 2800, null); // стін немає

        Result result = calc.calculate(extraction(List.of(ok, noPerimeter), List.of()));

        assertThat(result.totalFloorM2()).isEqualByComparingTo("52.00");
        assertThat(result.totalWallsNetM2()).isEqualByComparingTo("39.20"); // лише розраховане
        assertThat(result.totalPlinthM()).isEqualByComparingTo("14.00");
    }

    @Test
    void rectParserHandlesSpacesAndSeparators() {
        assertThat(RoomSurfaceCalc.rectPerimeterM("4990×3545")).isEqualTo(17.07);
        assertThat(RoomSurfaceCalc.rectPerimeterM("5 000 x 3 545")).isEqualTo(17.09);
        assertThat(RoomSurfaceCalc.rectPerimeterM("A×B + C×D")).isNull();
        assertThat(RoomSurfaceCalc.rectPerimeterM(null)).isNull();
    }

    // ---- fixtures --------------------------------------------------------------

    private static AlbumExtraction extraction(List<Room> rooms, List<Opening> openings) {
        Meta meta = new Meta("Тест", null, 1, 160.0, "multi_page_pdf", true, null);
        return new AlbumExtraction(meta, List.of(), List.of(), rooms, openings,
                List.of(), List.of(), List.of(), null,
                new PanelLocation(true, null), List.of(), List.of());
    }

    private static Room room(String name, String dims, Double perimeterM, Double areaSpec,
                             Integer ceilingHMm, String ceilingNote) {
        return new Room(1, "1", name, dims, perimeterM, null, areaSpec, ceilingHMm,
                ceilingNote, Status.COUNTED, false, null);
    }

    private static Opening opening(String roomA, String roomB, String kind,
                                   Integer widthMm, Integer heightMm, boolean toFloor) {
        return new Opening(1, roomA, roomB, kind, widthMm, heightMm, null, toFloor,
                null, Status.COUNTED, false, null);
    }
}
