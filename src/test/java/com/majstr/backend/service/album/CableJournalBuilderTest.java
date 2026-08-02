package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.FloorHeating;
import com.majstr.backend.service.album.AlbumExtraction.LightGroup;
import com.majstr.backend.service.album.AlbumExtraction.Lighting;
import com.majstr.backend.service.album.AlbumExtraction.PanelLocation;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import com.majstr.backend.service.album.AlbumExtraction.Room;
import com.majstr.backend.service.album.AlbumExtraction.Status;
import com.majstr.backend.service.album.CableJournalBuilder.Kind;
import com.majstr.backend.service.album.CableJournalBuilder.Row;
import com.majstr.backend.service.album.CableJournalBuilder.Scheme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journal built from a device list, checked against a REAL journal and the project it was
 * written for.
 *
 * <p>The fixture below is the electrical content of «Solone - House_2 1003.pdf» — two rooms of a
 * cottage, transcribed from its own specification tables: А-19 «Розетка 220v — 8», А-20/А-21
 * «Вимикач місцевий — 1, двоклавішний — 1» with groups annotated `гр.1 гр.2 гр.3`, and А-14
 * «електрична тепла підлога, 20,41 м²». The hand-annotated journal an electrician took to that site
 * is what these assertions are measured against, so a regression here means we have stopped
 * reproducing a sheet that a real master accepted.</p>
 */
class CableJournalBuilderTest {

    private final CableJournalBuilder builder = new CableJournalBuilder();

    private static final String BEDROOM = "Будинок 2. Спальня";
    private static final String LIVING = "Будинок 2. Вітальня";

    private static Room room(String number, String name) {
        return new Room(1, number, name, null, null, null, null, 2400, null,
                Status.FROM_SPEC, false, null);
    }

    private static ElectricalPoint point(String room, PointType type, int qty, String purpose) {
        return new ElectricalPoint(1, room, type, qty, 300, purpose, Status.COUNTED, false, null);
    }

    private static Lighting fixture(String room, String mark, String kind, int qty) {
        return new Lighting(1, room, kind, mark, qty, null, null, Status.FROM_SPEC, false, null);
    }

    private static LightGroup group(String id, String room) {
        return new LightGroup(id, 1, room, null, 1, Status.COUNTED, false, null);
    }

    /** Solone, House 2 — the project the real journal was written for. */
    private static AlbumExtraction solone() {
        return new AlbumExtraction(null, List.of(), List.of(),
                List.of(room("4", BEDROOM), room("5", LIVING)),
                List.of(),
                List.of(
                        // А-19: eight sockets over seven positions — 5.2 is a two-gang block.
                        point(BEDROOM, PointType.SOCKET, 1, null),
                        point(BEDROOM, PointType.SOCKET, 1, null),
                        point(BEDROOM, PointType.SOCKET, 1, null),
                        point(BEDROOM, PointType.SOCKET, 1, null),
                        point(LIVING, PointType.SOCKET, 1, null),
                        point(LIVING, PointType.SOCKET, 2, null),
                        point(LIVING, PointType.SOCKET, 1, null),
                        // А-20: one single-key switch in the bedroom, one two-key in the living room.
                        point(BEDROOM, PointType.SWITCH_1KEY, 1, null),
                        point(LIVING, PointType.SWITCH_2KEY, 1, null),
                        // А-18: the internal AC unit — «гр. АС» on the real sheet.
                        point(LIVING, PointType.CABLE_LEAD_220, 1, "кондиціонер")),
                List.of(
                        fixture(BEDROOM, "L 01", "Точковий світильник стельовий накладний", 6),
                        fixture(BEDROOM, "L 03", "Світильник настінний з вимикачем", 2),
                        fixture(LIVING, "L 01", "Точковий світильник стельовий накладний", 6)),
                // А-20/А-21: гр.3 in the bedroom; гр.1 and гр.2 in the living room, on one 2-key switch.
                List.of(group("гр.3", BEDROOM), group("гр.1", LIVING), group("гр.2", LIVING)),
                new FloorHeating(true, FloorHeating.SystemType.ELECTRIC,
                        List.of(new FloorHeating.Zone(1, 20.41, List.of(BEDROOM, LIVING),
                                Status.FROM_SPEC, false, null)),
                        true, null),
                new PanelLocation(false, null),
                List.of(), List.of());
    }

    private List<Row> rows(AlbumExtraction ex, Scheme scheme) {
        return builder.build(ex, CableJournalBuilder.Config.defaults().withScheme(scheme)).rows();
    }

    @Test
    void sevenSocketRUNSserveEIGHTsockets_whichIsWhatTheRealSheetDoes() {
        // The reconciliation that proves the whole idea: А-19's specification says «Розетка 220v — 8»,
        // and the real journal has seven socket rows. Both are right — Роз.5.2 is a two-gang block,
        // and ДБН В.2.5-23:2025 §7.66 counts a block as ONE socket, so one cable serves it.
        // A generator that emitted eight rows would be wrong against a sheet a master accepted.
        List<Row> sockets = rows(solone(), Scheme.KOROBKY).stream()
                .filter(r -> r.purpose().startsWith("Розетка"))
                .toList();

        assertThat(sockets).hasSize(7);
        assertThat(sockets).filteredOn(r -> r.purpose().contains("2 пости")).hasSize(1);
    }

    @Test
    void twoLightGroupsOnONEswitchTravelInONEcable_whichIsWhyTheRealSheetSays4x1_5() {
        // The living room carries гр.1 and гр.2 off a single two-key switch, and the real sheet's
        // trunk for it is NYM 4×1.5 while the single-group bedroom trunk is 3×1.5. That is not a
        // habit: cores = N + PE + one switched conductor per leg, and ДБН В.2.5-23:2025 §7.22 allows
        // the shared N/PE only within one group line — «Забороняється об'єднувати N та PЕ-провідники
        // різних групових ліній». Same switch ⇒ same line ⇒ legal to merge.
        List<Row> trunks = rows(solone(), Scheme.KOROBKY).stream()
                .filter(r -> r.kind() == Kind.TRUNK_LIGHT)
                .toList();

        assertThat(trunks).anySatisfy(r -> {
            assertThat(r.group()).isEqualTo("№ 1,2");
            assertThat(r.cores()).isEqualTo("4×1,5");
        });
        assertThat(trunks).anySatisfy(r -> {
            assertThat(r.group()).isEqualTo("№ 3");
            assertThat(r.cores()).isEqualTo("3×1,5");
        });
    }

    @Test
    void theUNDERFLOORlineIsThere_theOneTheStudioForgot() {
        // On the real sheet тепла підлога is not in the table at all — the electrician wrote
        // «РЩ – ТП – 6» by hand at the foot. It is a MANDATORY separate line (ДБН В.2.5-23:2025
        // §13.6), so enumerating from the device list is what stops it being forgotten.
        List<Row> dedicated = rows(solone(), Scheme.KOROBKY).stream()
                .filter(r -> r.kind() == Kind.DEDICATED)
                .toList();

        assertThat(dedicated).anySatisfy(r -> {
            assertThat(r.purpose()).contains("Тепла підлога").contains("20,4").contains("§13.6");
            assertThat(r.to()).isEqualTo("ТП");
        });
        // …and the air conditioner, which the sheet did carry as «гр. АС».
        assertThat(dedicated).anySatisfy(r -> assertThat(r.purpose()).contains("кондиціонер"));
    }

    @Test
    void groupsAreWrittenTheWayTheNormWritesThem() {
        // ДСТУ Б А.2.4-24:2008 ДОДАТОК А (обов'язковий) п.5 numbers a point's group «№ 2», «№ 4,5,6».
        // The studio's own «гр. 3» is habit; the corpus of 24 real projects shows even one studio
        // spelling it two ways across two jobs, so the norm's form is the only stable default.
        List<Row> all = rows(solone(), Scheme.KOROBKY);

        assertThat(all).allSatisfy(r ->
                assertThat(r.group()).matches("^(№ [0-9,]+)?$"));
        assertThat(all).noneMatch(r -> r.group().toLowerCase().contains("гр"));
        // A blank must stay blank rather than becoming a bare «№ ».
        assertThat(all).noneMatch(r -> r.group().equals("№ "));
    }

    @Test
    void theCABLEmarkIsSequentialAndUnique() {
        // «ЩО-1-Н1, ЩО-1-Н2 …» — Н for a cable, following the worked examples of ДСТУ Б А.2.4-21
        // Додатки В–Д ({@code 3ЩС-Н1}). A repeated mark would make two runs indistinguishable in
        // the wall, which is the one thing the marking exists to prevent.
        List<String> marks = rows(solone(), Scheme.KOROBKY).stream().map(Row::mark).toList();

        assertThat(marks).doesNotHaveDuplicates();
        assertThat(marks).allMatch(m -> m.startsWith("ЩО-1-Н"));
        assertThat(marks.get(0)).isEqualTo("ЩО-1-Н1");
    }

    @Test
    void theSCHEMEdecidesWhetherJunctionBoxesEXISTatAll() {
        // The measured finding from 24 real project sets: the studio that wires everything as зірка
        // never writes the word «коробка» once. Topology is not cosmetic — under зірка the whole box
        // layer disappears, and under шлейф sockets chain off each other instead of radiating.
        List<Row> boxes = rows(solone(), Scheme.KOROBKY);
        List<Row> star = rows(solone(), Scheme.ZIRKA);
        List<Row> chain = rows(solone(), Scheme.SHLEIF);

        assertThat(boxes).anyMatch(r -> r.to().startsWith("РК"));
        assertThat(star).noneMatch(r -> r.from().startsWith("РК") || r.to().startsWith("РК"));
        assertThat(star).allMatch(r -> r.kind() == Kind.CHAIN || r.from().equals("ЩО-1"));
        assertThat(chain).anyMatch(r -> r.kind() == Kind.CHAIN && r.purpose().startsWith("Розетка"));
        assertThat(boxes).noneMatch(r -> r.kind() == Kind.CHAIN && r.purpose().startsWith("Розетка"));
    }

    @Test
    void lightingHangsOffTheBOXnotOffTheSWITCH() {
        // Box-first, which is what the electrician's red pen changed the real sheet TO: the neutral
        // runs straight from the box to the fixture and only the phase goes through the switch. So a
        // switch is a leaf — nothing may be fed FROM it.
        List<Row> all = rows(solone(), Scheme.KOROBKY);

        assertThat(all).anySatisfy(r -> {
            assertThat(r.to()).isEqualTo("В4");
            assertThat(r.from()).isEqualTo("РК4");
        });
        assertThat(all).noneMatch(r -> r.from().startsWith("В"));
    }

    @Test
    void aRoomWithNoGroupNUMBERgetsAnEmptyCellAndAWarning_neverAnInventedNumber() {
        AlbumExtraction noGroups = new AlbumExtraction(null, List.of(), List.of(),
                List.of(room("4", BEDROOM)), List.of(),
                List.of(point(BEDROOM, PointType.SWITCH_1KEY, 1, null)),
                List.of(fixture(BEDROOM, "L 01", "стельовий", 2)),
                List.of(), null, new PanelLocation(true, "коридор"), List.of(), List.of());

        CableJournalBuilder.Result result =
                builder.build(noGroups, CableJournalBuilder.Config.defaults());

        assertThat(result.rows()).isNotEmpty();
        assertThat(result.rows()).allMatch(r -> r.group().isEmpty());
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("номер групи світла") && w.contains("вручну"));
    }

    @Test
    void aGroupThatReachesNoFIXTUREisFlagged_notInvented() {
        // Solone's living room declares гр.1 and гр.2, but А-21's specification lists «L 01» once for
        // the whole room. The real sheet has a «Світильник гр.2 (L01)» row; we cannot know which
        // fittings sit on which key, so гр.2 gets its trunk and switch and then a warning. Guessing
        // would put a cable to a luminaire that may not be there.
        CableJournalBuilder.Result result =
                builder.build(solone(), CableJournalBuilder.Config.defaults());

        assertThat(result.warnings()).anyMatch(w ->
                w.contains("група № 2") && w.contains("жоден світильник") && w.contains("вручну"));
        // …and the trunk for it is still emitted, because the leg exists whatever it reaches.
        assertThat(result.rows()).anyMatch(r -> r.group().contains("2") && r.kind() == Kind.TRUNK_LIGHT);
    }

    @Test
    void anUnknownPANELlocationIsAnOpenQuestion_becauseEveryTrunkStartsThere() {
        // Every магістраль begins at the board, and on the one real sheet both board runs were the
        // hand-written ones — they depend on the route through the house, not on room geometry.
        CableJournalBuilder.Result result =
                builder.build(solone(), CableJournalBuilder.Config.defaults());

        assertThat(result.openQuestions())
                .anyMatch(q -> q.contains("щита") && q.contains("уточнити"));
    }

    @Test
    void anEmptyAlbumProducesNoRowsAndSaysSo() {
        CableJournalBuilder.Result result = builder.build(
                new AlbumExtraction(null, List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), null, null, List.of(), List.of()),
                CableJournalBuilder.Config.defaults());

        assertThat(result.rows()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("не сформовано"));
    }

    @Test
    void aRoomNamedByTheAlbumKeepsItsNUMBERinNodeNames() {
        // Real albums name a room «Будинок 2. Спальня». Using that as a node would give «РКБудинок
        // 2. Спальня»; the room's own number is what belongs there.
        List<Row> all = rows(solone(), Scheme.KOROBKY);

        assertThat(all).anyMatch(r -> r.to().equals("РК4"));
        assertThat(all).anyMatch(r -> r.to().equals("Р5.2"));
        assertThat(all).noneMatch(r -> r.to().contains("Будинок"));
    }
}
