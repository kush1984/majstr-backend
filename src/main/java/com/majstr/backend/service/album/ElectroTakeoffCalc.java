package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.FloorHeating;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic electro-takeoff math over an {@link AlbumExtraction} — the "LLM extracts
 * facts, Java does arithmetic" half of the album-import feature. No I/O, no LLM: given the
 * extraction and a coefficient {@link Config}, produces cable lines, per-mark totals and
 * purchase bundles, chase (штроби) meters, back-box counts, and the honesty outputs
 * (open questions + warnings). Mirrors the takeoff methodology
 * ({@code PROMPT-takeoff-electro.md}): lighting = groups×trunk + points×perPoint;
 * sockets = points×perPoint; dedicated lines listed one by one; everything ×(1+reserve);
 * purchase rounded up to whole bundles; sanity check in m/m².
 *
 * <p>Honesty rules carried through: an unknown floor-heating system type or an unknown
 * panel location never silently defaults — they become {@code openQuestions} entries and
 * the affected lines are omitted (heating) or flagged (panel).</p>
 */
@Component
public class ElectroTakeoffCalc {

    /** Cable marks used in totals. Purely presentational strings — keep stable for the UI. */
    public static final String MARK_LIGHT = "3×1.5";
    public static final String MARK_SOCKET = "3×2.5";
    public static final String MARK_STOVE = "3×6";
    public static final String MARK_LOW_VOLT = "UTP cat.6";

    /**
     * All estimating coefficients in one place (later: editable per-tenant config).
     * Meters unless stated otherwise; shares/reserves are fractions.
     */
    public record Config(
            double trunkPerLightGroupM,
            double perLightPointM,
            double perSocketPointM,
            double dedicatedLineM,
            double perLowVoltPointM,
            double dropPerBlockM,
            double gklShare,
            double reserve,
            int bundleM,
            double sanityMinMPerM2,
            double sanityMaxMPerM2,
            double sanityMinAreaM2,
            double backboxReserve,
            List<String> dedicatedPurposeKeywords,
            List<String> stovePurposeKeywords
    ) {
        public static Config defaults() {
            return new Config(
                    16, 3, 10, 12, 14,
                    2.3, 0.15, 0.15, 100,
                    // м/м² перевіряємо лише від 60 м²: на малих квартирах фіксовані
                    // магістралі домінують і легітимно дають 15-30 м/м² (перевірено
                    // на реальних альбомах 34-44 м²).
                    6, 12, 60, 0.05,
                    List.of("духов", "мікрохвил", "посудомий", "холодильник",
                            "праль", "сушил", "бойлер", "рушник", "кондиціонер", "витяжк"),
                    List.of("плит", "варил"));
        }
    }

    /** One cable line of the takeoff (raw meters and with the reserve applied). */
    public record CableLine(String name, String mark, int rawM, int withReserveM) {}

    public record Result(
            List<CableLine> lines,
            Map<String, Integer> totalsByMark,
            Map<String, Integer> purchaseByMark,
            int lowVoltM,
            int chaseDrops,
            int chaseM,
            int backboxes,
            List<String> openQuestions,
            List<String> warnings
    ) {}

    public Result calculate(AlbumExtraction ex, Config cfg) {
        List<String> openQuestions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<CableLine> lines = new ArrayList<>();

        List<ElectricalPoint> points = nullSafe(ex.electricalPoints());
        List<AlbumExtraction.Lighting> lighting = nullSafe(ex.lighting());

        // ---- classify points -----------------------------------------------------
        int socketPoints = 0;      // general sockets on shared 3×2.5 lines
        int socketBlocks = 0;      // wall entries → chase drops
        int lowVoltPoints = 0;
        int switchQty = 0;
        int switchBlocks = 0;
        int thermostatQty = 0;
        int lightingPoints = 0;    // LED outputs/transformers/sensors on 3×1.5
        List<ElectricalPoint> dedicated = new ArrayList<>();

        for (ElectricalPoint p : points) {
            PointType type = p.pointType();
            if (type == null) {
                continue;
            }
            if (type.isDedicatedLead()
                    || (type.isGeneralSocket() && matchesAny(p.purpose(), cfg.dedicatedPurposeKeywords()))) {
                dedicated.add(p);
            } else if (type.isGeneralSocket()) {
                socketPoints += p.qty();
                socketBlocks++;
            } else if (type.isLowVoltage()) {
                lowVoltPoints += p.qty();
            } else if (type.isSwitch()) {
                switchQty += p.qty();
                switchBlocks++;
            } else if (type == PointType.THERMOSTAT) {
                thermostatQty += p.qty();
            } else if (type.isLightingPoint()) {
                lightingPoints += p.qty();
            }
        }
        for (AlbumExtraction.Lighting fixture : lighting) {
            lightingPoints += fixture.qty();
        }

        // ---- lighting line ---------------------------------------------------------
        int groups = nullSafe(ex.lightGroups()).size();
        if (groups == 0 && lightingPoints > 0) {
            // No switching plans in the album — a per-room group is the transparent fallback.
            groups = Math.max(1, nullSafe(ex.rooms()).size());
            openQuestions.add("Груп світла в альбомі немає — для оцінки прийнято "
                    + groups + " груп (по кімнатах); уточнити комутацію");
        }
        if (lightingPoints > 0) {
            double raw = groups * cfg.trunkPerLightGroupM() + lightingPoints * cfg.perLightPointM();
            lines.add(line("Освітлення: " + groups + " груп, " + lightingPoints + " точок",
                    MARK_LIGHT, raw, cfg));
        }

        // ---- socket line ------------------------------------------------------------
        if (socketPoints > 0) {
            lines.add(line("Розеткові лінії: " + socketPoints + " точок",
                    MARK_SOCKET, socketPoints * cfg.perSocketPointM(), cfg));
        }

        // ---- dedicated lines ----------------------------------------------------------
        for (ElectricalPoint p : dedicated) {
            String purpose = p.purpose() == null || p.purpose().isBlank()
                    ? p.pointType().name().toLowerCase(Locale.ROOT) : p.purpose();
            String mark = matchesAny(p.purpose(), cfg.stovePurposeKeywords()) ? MARK_STOVE
                    : p.pointType() == PointType.DOORPHONE_OUTLET ? MARK_LIGHT
                    : MARK_SOCKET;
            lines.add(line("Окрема лінія: " + purpose + " (" + p.room() + ")",
                    mark, cfg.dedicatedLineM(), cfg));
        }

        // ---- floor heating: honest branching, never a silent default -------------------
        FloorHeating fh = ex.floorHeating();
        if (fh != null && fh.present()) {
            if (fh.systemType() == FloorHeating.SystemType.ELECTRIC) {
                double area = nullSafe(fh.zones()).stream()
                        .map(FloorHeating.Zone::areaM2)
                        .filter(a -> a != null)
                        .mapToDouble(Double::doubleValue)
                        .sum();
                lines.add(line(String.format(Locale.ROOT,
                                "Тепла підлога (електрична, %.1f м²) + термостат", area),
                        MARK_LIGHT, cfg.dedicatedLineM(), cfg));
                if (!fh.thermostatsShown() && thermostatQty == 0) {
                    openQuestions.add("Терморегулятори теплої підлоги на планах не показані — "
                            + "уточнити кількість і місця");
                }
            } else if (fh.systemType() == FloorHeating.SystemType.UNKNOWN) {
                openQuestions.add("Тип теплої підлоги (електрична/водяна) в альбомі не вказаний — "
                        + "лінії живлення НЕ додані до розрахунку, уточнити в замовника");
            }
            // WATER: heating is plumbing, not our cable — nothing to add.
        }

        // ---- panel ----------------------------------------------------------------------
        if (ex.panelLocation() == null || !ex.panelLocation().known()) {
            openQuestions.add("Розташування щита в альбомі не показане — довжини трас "
                    + "розраховані за усередненими коефіцієнтами, уточнити місце щита");
        }

        // ---- totals + purchase ------------------------------------------------------------
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (CableLine l : lines) {
            totals.merge(l.mark(), l.withReserveM(), Integer::sum);
        }
        Map<String, Integer> purchase = new LinkedHashMap<>();
        totals.forEach((mark, meters) ->
                purchase.put(mark, ceilToBundle(meters, cfg.bundleM())));

        int lowVoltM = lowVoltPoints == 0 ? 0
                : withReserve(lowVoltPoints * cfg.perLowVoltPointM(), cfg.reserve());

        // ---- chases + back boxes ------------------------------------------------------------
        int drops = socketBlocks + dedicated.size() + switchBlocks + thermostatQty;
        int chaseM = (int) Math.ceil(drops * cfg.dropPerBlockM() * (1 - cfg.gklShare()));
        int backboxes = (int) Math.ceil(
                (socketPoints + dedicated.size() + lowVoltPoints + switchQty + thermostatQty)
                        * (1 + cfg.backboxReserve()));

        // ---- sanity + review summary ------------------------------------------------------
        int powerTotal = totals.values().stream().mapToInt(Integer::intValue).sum();
        Double area = ex.meta() == null ? null : ex.meta().totalAreaM2();
        if (area != null && area >= cfg.sanityMinAreaM2() && powerTotal > 0) {
            double perM2 = powerTotal / area;
            if (perM2 < cfg.sanityMinMPerM2() || perM2 > cfg.sanityMaxMPerM2()) {
                warnings.add(String.format(Locale.ROOT,
                        "Санітарна перевірка: %.1f м кабелю на м² (норма %.0f-%.0f) — "
                                + "перевірити вхідні дані", perM2,
                        cfg.sanityMinMPerM2(), cfg.sanityMaxMPerM2()));
            }
        }
        long verifyCount = points.stream().filter(ElectricalPoint::verify).count()
                + lighting.stream().filter(AlbumExtraction.Lighting::verify).count();
        if (verifyCount > 0) {
            warnings.add(verifyCount + " позицій зі статусом «звірити» — перевірити на екрані розпізнавання");
        }

        return new Result(lines, totals, purchase, lowVoltM, drops, chaseM, backboxes,
                openQuestions, warnings);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static CableLine line(String name, String mark, double rawM, Config cfg) {
        int raw = (int) Math.ceil(rawM);
        return new CableLine(name, mark, raw, withReserve(rawM, cfg.reserve()));
    }

    private static int withReserve(double meters, double reserve) {
        return (int) Math.ceil(meters * (1 + reserve));
    }

    static int ceilToBundle(int meters, int bundle) {
        if (meters <= 0) {
            return 0;
        }
        return ((meters + bundle - 1) / bundle) * bundle;
    }

    private static boolean matchesAny(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(lower::contains);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
