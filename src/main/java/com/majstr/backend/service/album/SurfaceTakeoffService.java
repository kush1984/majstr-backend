package com.majstr.backend.service.album;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.album.AlbumExtraction.Inventory;
import com.majstr.backend.service.album.AlbumExtraction.RoomsAndOpenings;
import com.majstr.backend.service.album.AlbumExtraction.Sheet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * The "площі по кімнатах" product flow — for painters/plasterers/tilers. Runs ONLY the
 * surface-relevant LLM passes (inventory + rooms/openings) and the deterministic
 * {@link RoomSurfaceCalc}; a master who needs areas never pays for electrical
 * recognition. The electrical sibling is {@link ElectroTakeoffService} — two independent
 * features over the same extractor, each with its own prompts and requests.
 *
 * <p>Synchronous CPU-wise but minutes-long wall-clock (two Opus passes) — callers run it
 * on an async job, never on a request thread. Thanks to prompt caching, if both features
 * run on the same album within the cache TTL, the second one reads the document from
 * cache (~10% input price).</p>
 */
@Service
@RequiredArgsConstructor
public class SurfaceTakeoffService {

    /** Sheet kinds the surface flow points the model at. */
    private static final Set<String> SURFACE_SHEETS = Set.of(
            "measurement_plan", "measurement_plan_after_remodel", "room_explication",
            "doors", "windows", "ceilings", "elevations");

    private final ClaudeAlbumExtractor extractor;
    private final RoomSurfaceCalc calc;

    public record SurfaceTakeoff(
            Inventory inventory,
            AlbumExtraction extraction,
            RoomSurfaceCalc.Result surfaces
    ) {}

    public SurfaceTakeoff run(byte[] albumPdf, String pageToFileMap) {
        Inventory inventory = extractor.inventory(albumPdf, pageToFileMap);
        requireDesignAlbum(inventory);

        List<Integer> sheets = sheetIndexes(inventory, SURFACE_SHEETS);
        RoomsAndOpenings rooms = extractor.extractRooms(albumPdf, sheets);

        AlbumExtraction extraction = new AlbumExtraction(
                inventory.meta(), inventory.sheets(), inventory.dataAvailability(),
                rooms.rooms(), rooms.openings(),
                List.of(), List.of(), List.of(), null, null,
                rooms.uncertain(), rooms.missing());

        return new SurfaceTakeoff(inventory, extraction, calc.calculate(extraction));
    }

    /** The upload is not a design album/measurement set → honest refusal, no expensive passes. */
    static void requireDesignAlbum(Inventory inventory) {
        if (inventory.meta() == null || !inventory.meta().isDesignAlbum()) {
            throw new AiExtractionException("error.album.unrecognized");
        }
    }

    /** Indexes of the sheets whose kind is in {@code kinds}; empty = let the model scan all. */
    static List<Integer> sheetIndexes(Inventory inventory, Set<String> kinds) {
        return inventory.sheets() == null ? List.of() : inventory.sheets().stream()
                .filter(Sheet::readable)
                .filter(s -> s.kind() != null && kinds.contains(s.kind()))
                .map(Sheet::index)
                .toList();
    }
}
