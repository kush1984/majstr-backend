package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.FloorHeating;
import com.majstr.backend.service.album.AlbumExtraction.Lighting;
import com.majstr.backend.service.album.AlbumExtraction.LightGroup;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds a КАБЕЛЬНИЙ ЖУРНАЛ — one row per cable run — from an {@link AlbumExtraction}.
 *
 * <p>Sibling of {@link ElectroTakeoffCalc}: same "LLM extracts facts, Java does the reasoning"
 * split, but where the calc answers <em>how many metres to buy</em>, this answers <em>which run
 * goes from what to what</em>. No I/O, no LLM. Lengths are deliberately absent — see below.</p>
 *
 * <h2>What is normative here, and what is ours</h2>
 * The artefact itself is a standard document: <b>ДСТУ Б А.2.4-24:2008 Форма 6 «Кабельний журнал
 * для живильної і розподільної мереж»</b> (mark ЕО; the силове sibling is Форма 7/8 of
 * ДСТУ Б А.2.4-21:2008). Both standards are in force from 2010-01-01 and were untouched by the
 * 2023 наказ № 175 wave. Form columns: {@code Маркування кабелю │ Траса: Початок │ Кінець │
 * Кабель: Проектний / Прокладений}, each cable block carrying марка, кількість×переріз and
 * довжина. Machine-generated layout is explicitly permitted — ДСТУ Б А.2.4-21 §5.14
 * («максимально наближені»), ДСТУ Б А.2.4-24 §4.4.3, ДСТУ 9243.10:2023 §4.12 («без втрати суті,
 * змісту та наповнення»).
 * <ul>
 *   <li><b>Normative:</b> the group column as {@code № N} — ДСТУ Б А.2.4-24 ДОДАТОК А
 *       <em>(обов'язковий)</em> п.5 and п.8. The cable mark shape {@code <вузол>-Н<n>}, where Н
 *       denotes a cable (Т a conduit), follows the worked examples in ДСТУ Б А.2.4-21 Додатки В–Д
 *       ({@code 3ЩС-Н1}, {@code 14-Н2}).</li>
 *   <li><b>Ours, because no norm defines it:</b> the node names. There is no letter code for a
 *       board, a distribution point or a junction box anywhere — ГОСТ 2.710-81 has no such class
 *       and has itself been Недіючий in Ukraine since 2019, and ДСТУ Б А.2.4-19:2008 gives only
 *       graphic symbols. The Cyrillic ЩО/РК/В/Р abbreviations are trade convention, so they live in
 *       {@link Config} rather than in the code.</li>
 *   <li><b>A choice, not data:</b> the topology. See {@link Scheme}.</li>
 * </ul>
 *
 * <h2>Why there are no lengths</h2>
 * ДСТУ Б А.2.4-21 §5.13 requires the length to include a «надбавка на вигини, повороти і відходи»
 * and then <em>never quantifies it</em>; no Ukrainian norm does. A per-run figure also needs device
 * positions, which a design album does not carry. So this pass emits the seven columns it can be
 * right about and leaves both length columns for the renderer to print empty — which is what the
 * form is for: it has a «Прокладений» block precisely so the installer writes the real number in.
 * On the one real sheet we have, that block was missing and the electrician wrote his measured
 * lengths over the design column.
 */
@Component
public class CableJournalBuilder {

    /**
     * How the runs are shaped. This is the installer's decision, not something a plan states, and
     * getting it wrong is worse than getting a length wrong: a wrong length is overwritten, wrong
     * topology means rows get rewritten. On the one real sheet we have, the electrician crossed out
     * the routing in red pen and re-fed two wall lights from a junction box.
     *
     * <p>Named as the trade names them (four independent Ukrainian sources agree on the three).</p>
     */
    public enum Scheme {
        /** Trunk to the first device, then device to device. Cheapest; one fault takes several points. */
        SHLEIF,
        /** One junction box per room; trunk to the box, then radially to each device. The common middle. */
        KOROBKY,
        /** Every device home-run from the board. No junction boxes exist at all. Most cable, biggest board. */
        ZIRKA
    }

    /** What a row IS, so the renderer and the merge can group without parsing Ukrainian prose. */
    public enum Kind {
        /** Board → room (lighting): магістраль живлення. */
        TRUNK_LIGHT,
        /** Board → room's socket box: магістраль живлення коробки. */
        TRUNK_SOCKET,
        /** Node → device. */
        DEVICE,
        /** Device → device: шлейф / ланцюг. */
        CHAIN,
        /** Its own line from the board, by norm or by practice. */
        DEDICATED
    }

    /**
     * Naming and cable choices. All of it is convention rather than norm (see the class javadoc),
     * which is exactly why it is configurable: the two ДСТУ that show worked examples do not even
     * agree with each other on where the digit goes — ДСТУ Б А.2.4-21 writes {@code 3ЩС} and
     * {@code 1ЩО}, ДСТУ Б А.2.4-24 writes {@code ЩО-1} and {@code РП-2}.
     */
    public record Config(
            String board,
            String boxPrefix,
            String switchPrefix,
            String socketPrefix,
            String cableMark,
            String lightCores,
            String socketCores,
            String cookerCores,
            int maxPostsPerGroup,
            Scheme scheme
    ) {
        public static Config defaults() {
            // ВВГнг-LS rather than NYM on purpose: the ДБН regulates EN 13501-6 fire classes and
            // names no marka at all, and NYM (German DIN VDE 0250-204, class Eca) cannot reach the
            // class a1 that §7.35 demands on escape routes. ВВГнг-LS is the mark a Ukrainian
            // designer can defend on any sheet of the set.
            // «ЩО-1» with the hyphen, because that is how the standard this form comes from writes
            // it in its own worked example (ДСТУ Б А.2.4-24 Додаток В: ЩО-5, ЩО-6, ЩО-12, РП-4).
            // Its силове sibling writes 3ЩС and 1ЩО with the digit in front — the two standards
            // genuinely disagree, so this is a default and not a rule.
            // 8 posts per socket line is the figure I used on Belgradska by hand and the one trade
            // sources repeat for a 16 A group; it is a flagging threshold, not a limit — no
            // Ukrainian norm sets one.
            return new Config("ЩО-1", "РК", "В", "Р",
                    "ВВГнг-LS", "3×1,5", "3×2,5", "3×6", 8, Scheme.KOROBKY);
        }

        public Config withScheme(Scheme s) {
            return new Config(board, boxPrefix, switchPrefix, socketPrefix, cableMark,
                    lightCores, socketCores, cookerCores, maxPostsPerGroup, s);
        }
    }

    /**
     * One row of Форма 6. The two «Довжина» cells are absent by design (see the class javadoc);
     * {@code cores} carries the form's «Кількість кабелів та переріз жил» as printed, e.g. 3×1,5.
     */
    public record Row(
            String mark,
            String from,
            String to,
            String group,
            String cableMark,
            String cores,
            String purpose,
            Kind kind
    ) {}

    public record Result(List<Row> rows, List<String> warnings, List<String> openQuestions) {}

    public Result build(AlbumExtraction ex, Config cfg) {
        List<Row> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> openQuestions = new ArrayList<>();
        Counter mark = new Counter(cfg.board());

        // Group numbers run in ONE sequence across the board, as a групові щитки table does
        // (ДСТУ Б А.2.4-24 Форма 3б, «Номери груп»). Lighting keeps the numbers the plan gave it;
        // socket groups take the next free ones, because nothing numbers those for us.
        Map<String, List<LightGroup>> lightByRoom = lightGroupsByRoom(ex, warnings);
        int nextGroup = firstFreeGroupNumber(ex);

        for (RoomRef room : roomsInOrder(ex)) {
            rows.addAll(lightingRows(room, lightByRoom.get(room.label()), ex, cfg, mark, warnings));
            List<Row> socketRows = socketRows(room, nextGroup, ex, cfg, mark);
            if (!socketRows.isEmpty()) {
                rows.addAll(socketRows);
                warnIfGroupOverloaded(room, socketRows, cfg, warnings);
                nextGroup++;
            }
        }

        rows.addAll(dedicatedRows(ex, cfg, mark, nextGroup, warnings));

        if (ex.panelLocation() == null || !ex.panelLocation().known()) {
            openQuestions.add("Розташування щита в альбомі не показане — початок магістралей "
                    + "вказано умовно як «" + cfg.board() + "», уточнити місце щита");
        }
        if (rows.isEmpty()) {
            warnings.add("Кабельний журнал не сформовано: в альбомі немає ні розеток, ні світла");
        }
        return new Result(rows, warnings, openQuestions);
    }

    // ---- lighting -----------------------------------------------------------------

    /**
     * Lighting is fed BOX-FIRST, not switch-first: the neutral runs straight from the box to the
     * fixture and only the phase goes through the switch. Five practice sources agree, and it is
     * also what the electrician on our one real sheet corrected the routing TO. So a switch is one
     * cable dropping from the box, never a node the fixtures hang off.
     */
    private List<Row> lightingRows(RoomRef room, List<LightGroup> groups, AlbumExtraction ex,
                                   Config cfg, Counter mark, List<String> warnings) {
        List<Lighting> fixtures = fixturesIn(ex, room.label());
        if (fixtures.isEmpty() && (groups == null || groups.isEmpty())) {
            return List.of();
        }
        List<Row> out = new ArrayList<>();
        List<String> groupNos = groups == null ? List.of()
                : groups.stream().map(g -> groupNumber(g.groupId())).filter(s -> !s.isBlank()).toList();
        if (groupNos.isEmpty()) {
            groupNos = List.of("");
            warnings.add("Приміщення " + room.label() + ": номер групи світла в альбомі не вказаний — "
                    + "рядки журналу сформовані без номера, проставити вручну");
        }

        String node = cfg.scheme() == Scheme.KOROBKY || cfg.scheme() == Scheme.SHLEIF
                ? cfg.boxPrefix() + room.code() : cfg.board();

        // Legs sharing ONE switch travel in ONE cable, and the core count follows: N + PE + one
        // switched conductor per leg. ДБН В.2.5-23:2025 §7.22 permits that only WITHIN a group
        // line — «Забороняється об'єднувати N та PЕ-провідники різних групових ліній» — so this
        // merge is legal exactly when the legs are switched from the same point, which is what a
        // two-gang switch on one breaker is. Separate breakers must stay separate runs.
        int legs = switchLegs(ex, room.label());
        boolean merged = legs > 1 && groupNos.size() == legs;
        if (!node.equals(cfg.board())) {
            if (merged) {
                out.add(new Row(mark.next(), cfg.board(), node, groupList(groupNos), cfg.cableMark(),
                        cores(legs), "Магістраль живлення", Kind.TRUNK_LIGHT));
            } else {
                for (String g : groupNos) {
                    out.add(new Row(mark.next(), cfg.board(), node, groupOf(g), cfg.cableMark(),
                            cfg.lightCores(), "Магістраль живлення", Kind.TRUNK_LIGHT));
                }
                if (legs > 1) {
                    warnings.add("Приміщення " + room.label() + ": вимикач на " + legs + " клавіші, а груп "
                            + "світла " + groupNos.size() + " — магістралі розведено окремо, звірити комутацію");
                }
            }
        }

        // The switch drop is L in + one switched conductor per KEY + PE. Its group cell may only
        // name groups when the keys and the groups actually correspond: on Belgradska a two-key
        // switch was emitted carrying «№ 1,19,20», three groups on two keys, which cannot exist.
        // When the counts disagree we do not know the mapping, so the cell stays empty and the
        // warning above is the whole answer.
        boolean keysMatchGroups = legs == groupNos.size();
        String sw = cfg.switchPrefix() + room.code();
        out.add(new Row(mark.next(), node, sw, keysMatchGroups ? groupList(groupNos) : "",
                cfg.cableMark(), cores(Math.max(legs, 1)), switchPurpose(legs), Kind.DEVICE));

        // One feed per fixture MARK (L 01, L 03 …) — the album's own «маркування освітлювальних
        // приладів». Several identical fittings on one mark are chained, which is the ланцюг row.
        Set<String> groupsWithFixtures = new LinkedHashSet<>();
        int gi = 0;
        for (Lighting f : fixtures) {
            String g = groupNos.get(Math.min(gi, groupNos.size() - 1));
            groupsWithFixtures.add(g);
            String to = fixtureNode(f);
            out.add(new Row(mark.next(), node, to, groupOf(g), cfg.cableMark(), cfg.lightCores(),
                    fixturePurpose(f, false), Kind.DEVICE));
            if (f.qty() > 1) {
                out.add(new Row(mark.next(), to, to, groupOf(g), cfg.cableMark(), cfg.lightCores(),
                        fixturePurpose(f, true), Kind.CHAIN));
            }
            gi++;
        }

        // A group with a trunk and a switch but no fixture is a leg that reaches nothing. It happens
        // when the album names more groups than fixture marks — on the real Solone sheet, гр.2 of the
        // living room has its own «Світильник гр.2 (L01)» row, but the album's specification lists
        // L 01 once for the whole room, so there is nothing here to attribute to it. Inventing the
        // row would be a guess about which fittings hang off which key; saying so is not.
        for (String g : groupNos) {
            if (!g.isBlank() && !groupsWithFixtures.contains(g)) {
                warnings.add("Приміщення " + room.label() + ", група № " + g + ": є магістраль і "
                        + "вимикач, але жоден світильник до неї не віднесений — в альбомі не сказано, "
                        + "які прилади на цій клавіші; додати рядок вручну");
            }
        }
        return out;
    }

    private static String switchPurpose(int legs) {
        return legs > 1 ? "Живлення вимикача (" + legs + " кл.)" : "Живлення вимикача";
    }

    private static String fixturePurpose(Lighting f, boolean chain) {
        String what = f.positionMark() == null || f.positionMark().isBlank()
                ? (f.fixtureKind() == null || f.fixtureKind().isBlank() ? "світильник" : f.fixtureKind())
                : f.positionMark();
        return chain
                ? "Ланцюг між світильниками " + what + " (×" + f.qty() + ")"
                : "Світильник " + what + (f.qty() > 1 ? " (живлення групи ×" + f.qty() + ")" : "");
    }

    private static String fixtureNode(Lighting f) {
        return f.positionMark() == null || f.positionMark().isBlank() ? "Світильник" : f.positionMark();
    }

    /** Keys (клавіші) on this room's switches — what decides how many switched legs travel together. */
    private static int switchLegs(AlbumExtraction ex, String room) {
        int legs = 0;
        for (ElectricalPoint p : nullSafe(ex.electricalPoints())) {
            if (!sameRoom(p.room(), room) || p.pointType() == null || !p.pointType().isSwitch()) {
                continue;
            }
            legs += switch (p.pointType()) {
                case SWITCH_2KEY, SWITCH_PASS_2KEY -> 2 * Math.max(1, p.qty());
                default -> Math.max(1, p.qty());
            };
        }
        return legs;
    }

    // ---- sockets ------------------------------------------------------------------

    private List<Row> socketRows(RoomRef room, int groupNo, AlbumExtraction ex, Config cfg,
                                 Counter mark) {
        List<ElectricalPoint> sockets = nullSafe(ex.electricalPoints()).stream()
                .filter(p -> sameRoom(p.room(), room.label()))
                .filter(p -> p.pointType() != null && p.pointType().isGeneralSocket())
                .toList();
        if (sockets.isEmpty()) {
            return List.of();
        }
        List<Row> out = new ArrayList<>();
        String group = groupOf(String.valueOf(groupNo));
        String node = cfg.scheme() == Scheme.ZIRKA ? cfg.board() : cfg.boxPrefix() + room.code();
        if (!node.equals(cfg.board())) {
            out.add(new Row(mark.next(), cfg.board(), node, group, cfg.cableMark(),
                    cfg.socketCores(), "Магістраль живлення коробки", Kind.TRUNK_SOCKET));
        }

        // One run per BLOCK, not per socket: ДБН В.2.5-23:2025 §7.66 counts «кілька розеток,
        // установлених в одному корпусі або в одному блоці» as one socket, and one cable feeds the
        // block. This is why seven runs can legitimately serve eight sockets.
        int n = 0;
        String previous = null;
        for (ElectricalPoint p : sockets) {
            n++;
            String to = cfg.socketPrefix() + room.code() + "." + n;
            boolean chain = cfg.scheme() == Scheme.SHLEIF && previous != null;
            out.add(new Row(mark.next(), chain ? previous : node, to, group, cfg.cableMark(),
                    cfg.socketCores(), socketPurpose(p), chain ? Kind.CHAIN : Kind.DEVICE));
            previous = to;
        }
        return out;
    }

    /**
     * All of a room's sockets land on one group, which is fine for a bedroom and wrong for a
     * kitchen-living room: Belgradska's produced thirteen posts on a single line, where the journal
     * written by hand had split them into a general group, a worktop group and four dedicated leads.
     *
     * <p>No Ukrainian norm caps sockets per group — ДБН В.2.5-23:2025 numbers points per area
     * (§7.66) and lamps per phase (§7.29) and stops there. So this cannot be an error, only a
     * flag: the cap is practice, it lives in {@link Config}, and splitting the group is the
     * electrician's call once he knows the appliance loads.</p>
     */
    private static void warnIfGroupOverloaded(RoomRef room, List<Row> socketRows, Config cfg,
                                             List<String> warnings) {
        int posts = 0;
        for (Row r : socketRows) {
            if (r.kind() == Kind.TRUNK_SOCKET) {
                continue;
            }
            posts += postsIn(r.purpose());
        }
        if (posts > cfg.maxPostsPerGroup()) {
            warnings.add("Приміщення " + room.label() + ": на одну розеткову групу припало " + posts
                    + " постів (орієнтир — до " + cfg.maxPostsPerGroup() + " на лінію 16 А). "
                    + "Норма межі не встановлює — розділити групу за потужністю техніки вирішує електрик");
        }
    }

    /** Posts named in a purpose line («Розетка (2 пости)»), defaulting to one. */
    private static int postsIn(String purpose) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\((\\d+)\\s*пост").matcher(purpose);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static String socketPurpose(ElectricalPoint p) {
        String posts = p.qty() > 1 ? "Розетка (" + p.qty() + " пости)" : "Розетка (1 пост)";
        if (p.pointType() == PointType.SOCKET_WET) {
            posts = posts + ", вологозахисна";
        }
        return p.purpose() == null || p.purpose().isBlank() ? posts : posts + " — " + p.purpose();
    }

    // ---- dedicated lines ----------------------------------------------------------

    /**
     * Lines that get their own circuit. Only four of these are actually mandated —
     * електроплита (ДБН В.2.5-23:2025 §7.23, §7.66), системи електричного опалення and
     * електричне нагрівання води (§13.6), комп'ютерні розетки (§7.16). Everything else here
     * (кондиціонер, пральна, посудомийна…) is our own practice, and the purpose text says which is
     * which so the master can drop ours and never drop the norm's.
     */
    private List<Row> dedicatedRows(AlbumExtraction ex, Config cfg, Counter mark, int firstGroup,
                                    List<String> warnings) {
        List<Row> out = new ArrayList<>();
        int group = firstGroup;

        int lead = 0;
        for (ElectricalPoint p : nullSafe(ex.electricalPoints())) {
            if (p.pointType() == null || !p.pointType().isDedicatedLead()) {
                continue;
            }
            boolean cooker = matches(p.purpose(), "плит", "варил");
            out.add(new Row(mark.next(), cfg.board(), leadNode(++lead),
                    groupOf(String.valueOf(group++)),
                    cfg.cableMark(), cooker ? cfg.cookerCores() : cfg.socketCores(),
                    leadPurpose(p, cooker), Kind.DEDICATED));
        }

        // Electric underfloor heating is a MANDATORY separate line (§13.6) and is the row most
        // easily forgotten: on the one real journal we have, it was missing from the table and the
        // electrician added «РЩ – ТП – 6» by hand at the foot of the sheet.
        FloorHeating fh = ex.floorHeating();
        if (fh != null && fh.present()) {
            if (fh.systemType() == FloorHeating.SystemType.ELECTRIC) {
                double area = nullSafe(fh.zones()).stream()
                        .map(FloorHeating.Zone::areaM2).filter(a -> a != null)
                        .mapToDouble(Double::doubleValue).sum();
                out.add(new Row(mark.next(), cfg.board(), "ТП", groupOf(String.valueOf(group++)),
                        cfg.cableMark(), cfg.socketCores(),
                        "Тепла підлога (електрична" + (area > 0 ? ", " + decimal(area) + " м²" : "")
                                + ") — окрема лінія за ДБН В.2.5-23:2025 §13.6",
                        Kind.DEDICATED));
            } else if (fh.systemType() == FloorHeating.SystemType.UNKNOWN) {
                warnings.add("Тип теплої підлоги (електрична/водяна) в альбомі не вказаний — "
                        + "рядок живлення НЕ додано; за §13.6 електрична вимагає окремої лінії");
            }
        }
        return out;
    }

    /**
     * A dedicated lead's node name. Numbered rather than named, because the purpose text is a free
     * sentence («духова шафа + мікрохвильова») and cutting it to fit a narrow «Кінець» column
     * produced things like «рушникосушар» and «варильна пов» — a node name an electrician has to
     * decode is worse than one he has to look up. The full wording stays in «Призначення».
     */
    private static String leadNode(int index) {
        return "ЕП-" + index;
    }

    private static String leadPurpose(ElectricalPoint p, boolean cooker) {
        String what = p.purpose() == null || p.purpose().isBlank()
                ? p.pointType().name().toLowerCase(Locale.ROOT) : p.purpose();
        String where = p.room() == null || p.room().isBlank() ? "" : " (" + p.room() + ")";
        return cooker
                ? "Електроплита" + where + " — окрема лінія за §7.23, мідь ≥6 мм²"
                : "Окрема лінія: " + what + where;
    }

    // ---- helpers ------------------------------------------------------------------

    /** «№ 3» — ДСТУ Б А.2.4-24 ДОДАТОК А (обов'язковий), п.5. Blank stays blank, never «№ ». */
    private static String groupOf(String number) {
        return number == null || number.isBlank() ? "" : "№ " + number.trim();
    }

    /** «№ 1,2» — the same annex's own example form for a run carrying several groups. */
    private static String groupList(List<String> numbers) {
        List<String> kept = numbers.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        return kept.isEmpty() ? "" : "№ " + String.join(",", kept);
    }

    /** Digits out of whatever the album called the group («гр.3», «Group 3», «3») — the norm wants a number. */
    static String groupNumber(String groupId) {
        if (groupId == null) {
            return "";
        }
        String digits = groupId.replaceAll("\\D+", "");
        return digits.isEmpty() ? "" : digits;
    }

    private static int firstFreeGroupNumber(AlbumExtraction ex) {
        int max = 0;
        for (LightGroup g : nullSafe(ex.lightGroups())) {
            String n = groupNumber(g.groupId());
            if (!n.isEmpty()) {
                try {
                    max = Math.max(max, Integer.parseInt(n));
                } catch (NumberFormatException ignored) {
                    // A group id with more digits than an int can hold is not a group number.
                }
            }
        }
        return max + 1;
    }

    /**
     * A number the way a Ukrainian sheet writes it — «20,4», not «20.4». Formatted under
     * {@code Locale.ROOT} and then switched, so the output never depends on the server's locale:
     * the same journal must read identically wherever it is rendered.
     */
    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
    }

    private static String cores(int legs) {
        // N + PE + one switched conductor per leg, in the form's «кількість×переріз» notation.
        return (legs + 2) + "×1,5";
    }

    private Map<String, List<LightGroup>> lightGroupsByRoom(AlbumExtraction ex, List<String> warnings) {
        Map<String, List<LightGroup>> byRoom = new LinkedHashMap<>();
        for (LightGroup g : nullSafe(ex.lightGroups())) {
            String room = g.controls() == null ? "" : g.controls().trim();
            if (room.isBlank()) {
                warnings.add("Група світла «" + g.groupId() + "» не привʼязана до приміщення — "
                        + "рядки для неї не сформовані");
                continue;
            }
            byRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(g);
        }
        return byRoom;
    }

    private static List<Lighting> fixturesIn(AlbumExtraction ex, String room) {
        return nullSafe(ex.lighting()).stream()
                .filter(f -> sameRoom(f.room(), room))
                .sorted(Comparator.comparing(f -> f.positionMark() == null ? "" : f.positionMark()))
                .toList();
    }

    /**
     * A room as the journal needs it: the LABEL the album's points and fixtures are keyed by, and a
     * short CODE for node names. The two differ more than you would expect — a real album names a
     * room «Будинок 2. Спальня», which is fine in a purpose column and useless as «РК…», so the
     * code prefers the room's own number and falls back to its position in the list.
     */
    record RoomRef(String label, String code) {}

    /**
     * Rooms in the order the journal should read them, which is the order the album lists them —
     * a journal is grouped by room because that is how it is worked through on site.
     */
    static List<RoomRef> roomsInOrder(AlbumExtraction ex) {
        Map<String, String> codeByLabel = new LinkedHashMap<>();
        for (AlbumExtraction.Room r : nullSafe(ex.rooms())) {
            String label = r.name() == null || r.name().isBlank() ? r.number() : r.name();
            if (label != null && !label.isBlank()) {
                String code = r.number() == null || r.number().isBlank()
                        ? String.valueOf(codeByLabel.size() + 1) : r.number().trim();
                codeByLabel.putIfAbsent(label.trim(), code);
            }
        }
        // An electro-only run carries no rooms list at all (ElectroTakeoffService passes List.of()),
        // so fall back to whatever the points and fixtures name — sorted, to keep output stable.
        Set<String> fromPoints = new TreeSet<>();
        for (ElectricalPoint p : nullSafe(ex.electricalPoints())) {
            if (p.room() != null && !p.room().isBlank()) {
                fromPoints.add(p.room().trim());
            }
        }
        for (Lighting f : nullSafe(ex.lighting())) {
            if (f.room() != null && !f.room().isBlank()) {
                fromPoints.add(f.room().trim());
            }
        }
        for (String label : fromPoints) {
            codeByLabel.putIfAbsent(label, String.valueOf(codeByLabel.size() + 1));
        }
        List<RoomRef> out = new ArrayList<>(codeByLabel.size());
        codeByLabel.forEach((label, code) -> out.add(new RoomRef(label, code)));
        return out;
    }

    private static boolean sameRoom(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean matches(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Sequential cable marks «ЩО1-Н1, ЩО1-Н2 …» — Н for a cable, per ДСТУ Б А.2.4-21 Додатки В–Д. */
    private static final class Counter {
        private final String board;
        private int n;

        Counter(String board) {
            this.board = board;
        }

        String next() {
            return board + "-Н" + (++n);
        }
    }
}
