package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.Opening;
import com.majstr.backend.service.album.AlbumExtraction.Room;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic room-surface takeoff over an {@link AlbumExtraction} — the "площі по
 * кімнатах" sibling of {@link ElectroTakeoffCalc}. Per room: floor and ceiling area,
 * perimeter, gross walls (P×H), openings deduction, net walls, plinth (skirting) length,
 * reveal (відкоси) linear metres and sill widths — plus house totals.
 *
 * <p>Formulas match the existing measurement domain ({@code MeasurementCalc}):
 * walls = Σ planes − Σ openings (w×h); reveals use the LINEAR default sides —
 * left + right + top, no bottom — i.e. {@code 2·H + W} per door/window/opening.</p>
 *
 * <p>Honesty carried through from the extraction contract: nothing is guessed.
 * A missing ceiling height or underivable perimeter leaves the dependent values
 * {@code null} with a note; an opening without both dimensions is skipped in the
 * deduction/reveals and reported, so the master sees WHAT is missing instead of a
 * silently-wrong number. A door shared by two rooms is deducted from both walls
 * (each side of the wall loses the hole).</p>
 */
@Component
public class RoomSurfaceCalc {

    /** "4990×3545" / "4990 x 3545" — a single rectangle in mm (spaces as digit groups allowed). */
    private static final Pattern RECT_MM = Pattern.compile(
            "^\\s*(\\d[\\d\\s]*)\\s*[x×]\\s*(\\d[\\d\\s]*)\\s*$");

    /** Opening kinds that interrupt the skirting board when they reach the floor. */
    private static final Pattern REVEAL_KINDS = Pattern.compile(
            "door_interior|door_entrance|door_exterior|door_sliding|window|window_panoramic|opening");

    /** Per-room surface takeoff. Nullable values = "не розраховано" (причина у notes). */
    public record RoomSurfaces(
            int floor,
            String name,
            BigDecimal floorAreaM2,
            BigDecimal ceilingAreaM2,
            BigDecimal perimeterM,
            Integer wallHeightMm,
            BigDecimal wallsGrossM2,
            BigDecimal openingsAreaM2,
            BigDecimal wallsNetM2,
            BigDecimal plinthM,
            BigDecimal revealsM,
            BigDecimal sillsM,
            int windowCount,
            boolean verify,
            List<String> notes
    ) {}

    public record Result(
            List<RoomSurfaces> rooms,
            BigDecimal totalFloorM2,
            BigDecimal totalCeilingM2,
            BigDecimal totalWallsNetM2,
            BigDecimal totalPlinthM,
            BigDecimal totalRevealsM,
            BigDecimal totalSillsM,
            List<String> warnings
    ) {}

    public Result calculate(AlbumExtraction ex) {
        List<Room> rooms = ex.rooms() == null ? List.of() : ex.rooms();
        List<Opening> openings = ex.openings() == null ? List.of() : ex.openings();
        List<RoomSurfaces> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int roomsWithoutPerimeter = 0;
        int openingsWithoutDims = 0;

        for (Room room : rooms) {
            List<String> notes = new ArrayList<>();
            boolean verify = room.verify();

            // ---- floor & ceiling ------------------------------------------------
            Double floorArea = room.areaSpecM2() != null ? room.areaSpecM2() : room.areaCalcM2();
            if (floorArea == null) {
                notes.add("площа підлоги відсутня в альбомі");
            }
            boolean sloped = room.ceilingNote() != null
                    && room.ceilingNote().toLowerCase(Locale.ROOT).contains("скіс");
            if (sloped) {
                verify = true;
                notes.add("мансардні скоси — площі стін/стелі наближені, зняти по факту");
            }

            // ---- perimeter -------------------------------------------------------
            Double perimeter = room.perimeterM();
            if (perimeter == null) {
                perimeter = rectPerimeterM(room.dimsMm());
            }
            if (perimeter == null) {
                roomsWithoutPerimeter++;
                notes.add("периметр не визначений (складний контур без perimeter_m)");
            }

            // ---- openings of this room -------------------------------------------
            List<Opening> mine = openings.stream()
                    .filter(o -> touches(o, room.name()))
                    .toList();

            double openingsArea = 0;      // m², only openings with BOTH dims
            int unknownDims = 0;
            double reveals = 0;           // п.м: 2·H + W per door/window/opening
            double sills = 0;
            int windows = 0;
            double toFloorWidths = 0;     // m, for the plinth deduction
            boolean toFloorWidthUnknown = false;

            for (Opening o : mine) {
                boolean isWindow = o.kind() != null && o.kind().startsWith("window");
                if (isWindow) {
                    windows++;
                    if (o.widthMm() != null) {
                        sills += o.widthMm() / 1000.0;
                    }
                }
                if (o.toFloor()) {
                    if (o.widthMm() != null) {
                        toFloorWidths += o.widthMm() / 1000.0;
                    } else {
                        toFloorWidthUnknown = true;
                    }
                }
                boolean revealKind = o.kind() != null && REVEAL_KINDS.matcher(o.kind()).matches();
                if (o.widthMm() != null && o.heightMm() != null) {
                    openingsArea += o.widthMm() / 1000.0 * o.heightMm() / 1000.0;
                    if (revealKind) {
                        reveals += 2 * (o.heightMm() / 1000.0) + o.widthMm() / 1000.0;
                    }
                } else {
                    unknownDims++;
                }
            }
            if (unknownDims > 0) {
                openingsWithoutDims += unknownDims;
                verify = true;
                notes.add(unknownDims + " проріз(ів) без повних розмірів — не відняті від стін "
                        + "і не враховані у відкосах");
            }
            if (toFloorWidthUnknown) {
                verify = true;
                notes.add("проріз до підлоги без ширини — плінтус завищений");
            }

            // ---- walls ---------------------------------------------------------------
            BigDecimal wallsGross = null;
            BigDecimal wallsNet = null;
            BigDecimal plinth = null;
            if (perimeter != null && room.ceilingHMm() != null) {
                double gross = perimeter * room.ceilingHMm() / 1000.0;
                wallsGross = m2(gross);
                wallsNet = m2(Math.max(0, gross - openingsArea));
            } else if (perimeter != null) {
                notes.add("висота стелі відсутня — площі стін не розраховані");
            }
            if (perimeter != null) {
                plinth = m2(Math.max(0, perimeter - toFloorWidths));
            }

            out.add(new RoomSurfaces(
                    room.floor(),
                    room.name(),
                    floorArea == null ? null : m2(floorArea),
                    floorArea == null ? null : m2(floorArea), // стеля пласка = підлозі; скоси → verify
                    perimeter == null ? null : m2(perimeter),
                    room.ceilingHMm(),
                    wallsGross,
                    m2(openingsArea),
                    wallsNet,
                    plinth,
                    m2(reveals),
                    m2(sills),
                    windows,
                    verify,
                    notes));
        }

        if (roomsWithoutPerimeter > 0) {
            warnings.add(roomsWithoutPerimeter + " кімнат(и) без периметра — стіни/плінтус "
                    + "для них не розраховані");
        }
        if (openingsWithoutDims > 0) {
            warnings.add(openingsWithoutDims + " проріз(ів) без повних розмірів (типово: в альбомі "
                    + "немає висот вікон) — стіни нетто по цих кімнатах ЗАВИЩЕНІ, звірити");
        }

        return new Result(out,
                sum(out, RoomSurfaces::floorAreaM2),
                sum(out, RoomSurfaces::ceilingAreaM2),
                sum(out, RoomSurfaces::wallsNetM2),
                sum(out, RoomSurfaces::plinthM),
                sum(out, RoomSurfaces::revealsM),
                sum(out, RoomSurfaces::sillsM),
                warnings);
    }

    // ---- helpers ---------------------------------------------------------------------

    /** Perimeter of a plain "A×B" mm rectangle; null for complex/unparseable dims. */
    static Double rectPerimeterM(String dimsMm) {
        if (dimsMm == null) {
            return null;
        }
        Matcher m = RECT_MM.matcher(dimsMm);
        if (!m.matches()) {
            return null;
        }
        long a = Long.parseLong(m.group(1).replaceAll("\\s", ""));
        long b = Long.parseLong(m.group(2).replaceAll("\\s", ""));
        return 2 * (a + b) / 1000.0;
    }

    private static boolean touches(Opening o, String roomName) {
        return equalsIgnoreCaseTrim(o.roomA(), roomName) || equalsIgnoreCaseTrim(o.roomB(), roomName);
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static BigDecimal m2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<RoomSurfaces> rooms,
                                  java.util.function.Function<RoomSurfaces, BigDecimal> field) {
        return rooms.stream()
                .map(field)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
