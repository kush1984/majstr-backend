package com.majstr.backend.service.album;

import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.HeatingResult;
import com.majstr.backend.service.album.AlbumExtraction.Inventory;
import com.majstr.backend.service.album.AlbumExtraction.LightingResult;
import com.majstr.backend.service.album.AlbumExtraction.PointsResult;
import com.majstr.backend.service.album.AlbumExtraction.Sheet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The "електрика" product flow — for electricians. Runs ONLY the electrical LLM passes
 * (inventory + points per floor + lighting/groups + heating/panel) and the deterministic
 * {@link ElectroTakeoffCalc}; no rooms/areas extraction is paid for. The surfaces sibling
 * is {@link SurfaceTakeoffService} — two independent features over the same extractor,
 * each with its own prompts and requests.
 */
@Service
@RequiredArgsConstructor
public class ElectroTakeoffService {

    private static final Set<String> POINT_SHEETS = Set.of("sockets_switches");
    private static final Set<String> LIGHTING_SHEETS =
            Set.of("light_fixtures", "light_switching", "light_groups");
    private static final Set<String> HEATING_SHEETS =
            Set.of("floor_heating", "ventilation_ac", "sockets_switches");

    private final ClaudeAlbumExtractor extractor;
    private final ElectroTakeoffCalc calc;

    public record ElectroTakeoff(
            Inventory inventory,
            AlbumExtraction extraction,
            ElectroTakeoffCalc.Result takeoff
    ) {}

    public ElectroTakeoff run(byte[] albumPdf, String pageToFileMap) {
        Inventory inventory = extractor.inventory(albumPdf, pageToFileMap);
        SurfaceTakeoffService.requireDesignAlbum(inventory);

        // One points pass per floor that actually has a sockets/switches plan.
        List<ElectricalPoint> points = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int floor : floorsWithPointPlans(inventory)) {
            List<Integer> sheets = floorSheetIndexes(inventory, POINT_SHEETS, floor);
            PointsResult result = extractor.extractPoints(albumPdf, floor, sheets, List.of());
            points.addAll(result.electricalPoints());
            uncertain.addAll(result.uncertain());
            missing.addAll(result.missing());
        }

        LightingResult lighting = extractor.extractLighting(albumPdf,
                SurfaceTakeoffService.sheetIndexes(inventory, LIGHTING_SHEETS));
        uncertain.addAll(lighting.uncertain());
        missing.addAll(lighting.missing());

        HeatingResult heating = extractor.extractHeating(albumPdf,
                SurfaceTakeoffService.sheetIndexes(inventory, HEATING_SHEETS));
        points.addAll(heating.electricalPoints());
        uncertain.addAll(heating.uncertain());
        missing.addAll(heating.missing());

        AlbumExtraction extraction = new AlbumExtraction(
                inventory.meta(), inventory.sheets(), inventory.dataAvailability(),
                List.of(), List.of(),
                points, lighting.lighting(), lighting.lightGroups(),
                heating.floorHeating(), heating.panelLocation(),
                uncertain, missing);

        return new ElectroTakeoff(inventory, extraction,
                calc.calculate(extraction, ElectroTakeoffCalc.Config.defaults()));
    }

    /** Floors that have a readable sockets/switches sheet; a null floor counts as floor 1. */
    static Set<Integer> floorsWithPointPlans(Inventory inventory) {
        Set<Integer> floors = new TreeSet<>();
        if (inventory.sheets() != null) {
            for (Sheet s : inventory.sheets()) {
                if (s.readable() && POINT_SHEETS.contains(s.kind())) {
                    floors.add(s.floor() == null ? 1 : s.floor());
                }
            }
        }
        return floors;
    }

    private static List<Integer> floorSheetIndexes(Inventory inventory, Set<String> kinds, int floor) {
        return inventory.sheets() == null ? List.of() : inventory.sheets().stream()
                .filter(Sheet::readable)
                .filter(s -> s.kind() != null && kinds.contains(s.kind()))
                .filter(s -> (s.floor() == null ? 1 : s.floor()) == floor)
                .map(Sheet::index)
                .toList();
    }
}
