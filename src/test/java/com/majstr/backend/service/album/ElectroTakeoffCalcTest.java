package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.FloorHeating;
import com.majstr.backend.service.album.AlbumExtraction.LightGroup;
import com.majstr.backend.service.album.AlbumExtraction.Lighting;
import com.majstr.backend.service.album.AlbumExtraction.Meta;
import com.majstr.backend.service.album.AlbumExtraction.PanelLocation;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import com.majstr.backend.service.album.AlbumExtraction.Room;
import com.majstr.backend.service.album.AlbumExtraction.Status;
import com.majstr.backend.service.album.ElectroTakeoffCalc.CableLine;
import com.majstr.backend.service.album.ElectroTakeoffCalc.Config;
import com.majstr.backend.service.album.ElectroTakeoffCalc.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the takeoff arithmetic against hand-computed numbers (defaults: trunk 16 м/групу,
 * 3 м/світлоточку, 10 м/розеткоточку, 12 м виділена лінія, запас 15%, спуск 2,3 м,
 * частка ГКЛ 15%, бухта 100 м) — and the honesty branches: an unknown floor-heating type
 * or unknown panel location must produce open questions, never silent defaults.
 */
class ElectroTakeoffCalcTest {

    private final ElectroTakeoffCalc calc = new ElectroTakeoffCalc();
    private final Config cfg = Config.defaults();

    @Test
    void lightingLineUsesGroupsAndPoints() {
        // 2 групи, світильники 5 + LED-вивід 1 = 6 точок → 2×16 + 6×3 = 50 → ×1.15 = 57.5 → 58
        AlbumExtraction ex = extraction(
                List.of(point(PointType.LED_OUTPUT, 1, null)),
                List.of(fixture(5)),
                List.of(group("гр.1"), group("гр.2")),
                null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        CableLine light = lineByMark(result, ElectroTakeoffCalc.MARK_LIGHT);
        assertThat(light.rawM()).isEqualTo(50);
        assertThat(light.withReserveM()).isEqualTo(58);
        assertThat(result.openQuestions()).isEmpty(); // групи є, щит відомий
    }

    @Test
    void socketLineSumsPointsAcrossBlocks() {
        // 4 + 2 = 6 точок → 60 → ×1.15 = 69
        AlbumExtraction ex = extraction(
                List.of(point(PointType.SOCKET, 4, null), point(PointType.SOCKET_WET, 2, null)),
                List.of(), List.of(), null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        CableLine sockets = lineByMark(result, ElectroTakeoffCalc.MARK_SOCKET);
        assertThat(sockets.rawM()).isEqualTo(60);
        assertThat(sockets.withReserveM()).isEqualTo(69);
    }

    @Test
    void dedicatedLinesByPurposeAndStoveMark() {
        // Варильна → 3×6; духовка-розетка виключається з загальних і стає окремою лінією 3×2.5.
        AlbumExtraction ex = extraction(
                List.of(point(PointType.POWER_OUTLET_220, 1, "варильна поверхня"),
                        point(PointType.SOCKET, 2, "духовка і мікрохвильовка"),
                        point(PointType.SOCKET, 3, null)),
                List.of(), List.of(), null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        // 12 → ×1.15 = 13.8 → 14
        assertThat(lineByMark(result, ElectroTakeoffCalc.MARK_STOVE).withReserveM()).isEqualTo(14);
        // Загальні розетки: лише 3 точки (духовка пішла у виділені) → 30 → 35 (34.5)
        assertThat(lineByMark(result, ElectroTakeoffCalc.MARK_SOCKET).name()).contains("3 точок");
        // Разом 3×2.5: загальна 35 + виділена духовка 14 = 49
        assertThat(result.totalsByMark().get(ElectroTakeoffCalc.MARK_SOCKET)).isEqualTo(49);
    }

    @Test
    void purchaseRoundsUpToWholeBundles() {
        assertThat(ElectroTakeoffCalc.ceilToBundle(1, 100)).isEqualTo(100);
        assertThat(ElectroTakeoffCalc.ceilToBundle(100, 100)).isEqualTo(100);
        assertThat(ElectroTakeoffCalc.ceilToBundle(101, 100)).isEqualTo(200);
        assertThat(ElectroTakeoffCalc.ceilToBundle(0, 100)).isZero();
    }

    @Test
    void chasesCountBlocksNotPoints() {
        // Спуски = блоки: 2 розеткові блоки + 1 виділена + 1 вимикачний блок = 4
        // → 4 × 2.3 × 0.85 = 7.82 → 8 м
        AlbumExtraction ex = extraction(
                List.of(point(PointType.SOCKET, 4, null),
                        point(PointType.SOCKET, 2, null),
                        point(PointType.POWER_OUTLET_220, 1, "кондиціонер"),
                        point(PointType.SWITCH_2KEY, 1, null)),
                List.of(), List.of(), null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.chaseDrops()).isEqualTo(4);
        assertThat(result.chaseM()).isEqualTo(8);
    }

    @Test
    void backboxesCountPointsWithReserve() {
        // Підрозетники = точки: 6 розеток + 1 виділена + 2 слаботочні + 1 вимикач = 10
        // → ×1.05 = 10.5 → 11
        AlbumExtraction ex = extraction(
                List.of(point(PointType.SOCKET, 6, null),
                        point(PointType.POWER_OUTLET_220, 1, "витяжка"),
                        point(PointType.SOCKET_TV, 1, null),
                        point(PointType.SOCKET_NET, 1, null),
                        point(PointType.SWITCH_1KEY, 1, null)),
                List.of(), List.of(), null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.backboxes()).isEqualTo(11);
        // Слаботочка окремо від силових: 2 × 14 = 28 → ×1.15 = 32.2 → 33
        assertThat(result.lowVoltM()).isEqualTo(33);
        assertThat(result.totalsByMark()).doesNotContainKey(ElectroTakeoffCalc.MARK_LOW_VOLT);
    }

    @Test
    void electricFloorHeatingAddsLine() {
        FloorHeating fh = new FloorHeating(true, FloorHeating.SystemType.ELECTRIC,
                List.of(new FloorHeating.Zone(1, 6.6, List.of("Ванна"), Status.FROM_SPEC,
                        false, null)),
                false, null);
        AlbumExtraction ex = extraction(List.of(), List.of(), List.of(), fh, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.lines()).anyMatch(l -> l.name().contains("Тепла підлога"));
        // Терморегулятори не показані і точок-термостатів немає → відкрите питання.
        assertThat(result.openQuestions()).anyMatch(q -> q.contains("Терморегулятори"));
    }

    @Test
    void unknownFloorHeatingTypeIsAnOpenQuestionNotALine() {
        FloorHeating fh = new FloorHeating(true, FloorHeating.SystemType.UNKNOWN,
                List.of(), false, null);
        AlbumExtraction ex = extraction(List.of(), List.of(), List.of(), fh, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.lines()).noneMatch(l -> l.name().contains("Тепла підлога"));
        assertThat(result.openQuestions()).anyMatch(q -> q.contains("Тип теплої підлоги"));
    }

    @Test
    void unknownPanelLocationAndMissingGroupsBecomeOpenQuestions() {
        // Щит невідомий + світло без груп: 1 кімната → fallback 1 група.
        AlbumExtraction ex = extraction(
                List.of(), List.of(fixture(3)), List.of(),
                null, new PanelLocation(false, null),
                List.of(room("Кухня")));

        Result result = calc.calculate(ex, cfg);

        assertThat(result.openQuestions())
                .anyMatch(q -> q.contains("щита"))
                .anyMatch(q -> q.contains("Груп світла"));
        // Fallback: 1 група × 16 + 3 точки × 3 = 25 → 29 (28.75)
        assertThat(lineByMark(result, ElectroTakeoffCalc.MARK_LIGHT).withReserveM()).isEqualTo(29);
    }

    @Test
    void sanityWarningWhenMetersPerSquareMeterOutOfRange() {
        // 100 м² і 200 розеткових точок → 2000×1.15/100 = 23 м/м² > 12 → попередження.
        AlbumExtraction dense = extraction(100.0,
                List.of(point(PointType.SOCKET, 200, null)),
                List.of(), List.of(), null, panelKnown(), null);
        assertThat(calc.calculate(dense, cfg).warnings())
                .anyMatch(w -> w.contains("Санітарна перевірка"));

        // Мала площа (< 60 м²): фіксовані магістралі легітимно дають 15-30 м/м² —
        // перевірка НЕ шумить (перевірено на реальних альбомах 34-44 м²).
        AlbumExtraction small = extraction(40.0,
                List.of(point(PointType.SOCKET, 30, null)),
                List.of(), List.of(), null, panelKnown(), null);
        assertThat(calc.calculate(small, cfg).warnings())
                .noneMatch(w -> w.contains("Санітарна"));

        // Площа невідома → санітарної перевірки немає (і жодного NPE).
        AlbumExtraction noArea = extraction(null,
                List.of(point(PointType.SOCKET, 10, null)),
                List.of(), List.of(), null, panelKnown(), null);
        assertThat(calc.calculate(noArea, cfg).warnings())
                .noneMatch(w -> w.contains("Санітарна"));
    }

    @Test
    void verifyFlagsSurfaceAsAReviewWarning() {
        ElectricalPoint flagged = new ElectricalPoint(1, "Кухня", PointType.SOCKET, 6, 1000,
                null, Status.COUNTED, true, "розподіл висот неоднозначний");
        AlbumExtraction ex = extraction(List.of(flagged), List.of(), List.of(),
                null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.warnings()).anyMatch(w -> w.contains("звірити"));
    }

    @Test
    void emptyExtractionYieldsEmptyResultWithoutErrors() {
        AlbumExtraction ex = extraction(List.of(), List.of(), List.of(), null, panelKnown(), null);

        Result result = calc.calculate(ex, cfg);

        assertThat(result.lines()).isEmpty();
        assertThat(result.totalsByMark()).isEmpty();
        assertThat(result.purchaseByMark()).isEmpty();
        assertThat(result.chaseM()).isZero();
        assertThat(result.backboxes()).isZero();
    }

    // ---- fixtures -----------------------------------------------------------------

    private static AlbumExtraction extraction(List<ElectricalPoint> points, List<Lighting> lighting,
                                              List<LightGroup> groups, FloorHeating fh,
                                              PanelLocation panel, List<Room> rooms) {
        return extraction(160.0, points, lighting, groups, fh, panel, rooms);
    }

    private static AlbumExtraction extraction(Double areaM2, List<ElectricalPoint> points,
                                              List<Lighting> lighting, List<LightGroup> groups,
                                              FloorHeating fh, PanelLocation panel,
                                              List<Room> rooms) {
        // 160 м² за замовчуванням — щоб санітарна перевірка не шуміла у фокусних тестах.
        Meta meta = new Meta("Тест", null, 1, areaM2, "multi_page_pdf", true, null);
        return new AlbumExtraction(meta, List.of(), List.of(),
                rooms == null ? List.of() : rooms,
                List.of(), points, lighting, groups, fh, panel, List.of(), List.of());
    }

    private static ElectricalPoint point(PointType type, int qty, String purpose) {
        return new ElectricalPoint(1, "Кімната", type, qty, 300, purpose,
                Status.COUNTED, false, null);
    }

    private static Lighting fixture(int qty) {
        return new Lighting(1, "Кімната", "recessed", null, qty, null, null,
                Status.COUNTED, false, null);
    }

    private static LightGroup group(String id) {
        return new LightGroup(id, 1, "світло", "1-кл біля дверей", 1,
                Status.FROM_SPEC, false, null);
    }

    private static Room room(String name) {
        return new Room(1, "1", name, "3000×4000", 14.0, 12.0, 12.0, 2800, null,
                Status.FROM_SPEC, false, null);
    }

    private static PanelLocation panelKnown() {
        return new PanelLocation(true, "коридор");
    }

    private static CableLine lineByMark(Result result, String mark) {
        return result.lines().stream()
                .filter(l -> l.mark().equals(mark))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line with mark " + mark
                        + " in " + result.lines()));
    }
}
