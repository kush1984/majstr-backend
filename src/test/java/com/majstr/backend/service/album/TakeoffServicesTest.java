package com.majstr.backend.service.album;

import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.album.AlbumExtraction.DataAvailability;
import com.majstr.backend.service.album.AlbumExtraction.ElectricalPoint;
import com.majstr.backend.service.album.AlbumExtraction.FloorHeating;
import com.majstr.backend.service.album.AlbumExtraction.HeatingResult;
import com.majstr.backend.service.album.AlbumExtraction.Inventory;
import com.majstr.backend.service.album.AlbumExtraction.LightingResult;
import com.majstr.backend.service.album.AlbumExtraction.Meta;
import com.majstr.backend.service.album.AlbumExtraction.Opening;
import com.majstr.backend.service.album.AlbumExtraction.PanelLocation;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import com.majstr.backend.service.album.AlbumExtraction.PointsResult;
import com.majstr.backend.service.album.AlbumExtraction.Room;
import com.majstr.backend.service.album.AlbumExtraction.RoomsAndOpenings;
import com.majstr.backend.service.album.AlbumExtraction.Sheet;
import com.majstr.backend.service.album.AlbumExtraction.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Locks the two-feature split: the surfaces flow runs ONLY inventory + rooms passes,
 * the electro flow runs ONLY inventory + points/lighting/heating passes (per floor with
 * a sockets plan) — and a non-album upload is refused after the cheap inventory pass,
 * before any expensive extraction.
 */
@ExtendWith(MockitoExtension.class)
class TakeoffServicesTest {

    private static final byte[] PDF = "pdf".getBytes();

    @Mock private ClaudeAlbumExtractor extractor;

    @InjectMocks private SurfaceTakeoffService surfaceService;

    @Test
    void surfaceFlowRunsOnlyItsOwnPasses() {
        surfaceService = new SurfaceTakeoffService(extractor, new RoomSurfaceCalc());
        given(extractor.inventory(PDF, null)).willReturn(inventory(
                sheet(3, "measurement_plan_after_remodel", 1),
                sheet(5, "room_explication", 1),
                sheet(24, "sockets_switches", 1)));
        given(extractor.extractRooms(eq(PDF), anyList())).willReturn(new RoomsAndOpenings(
                List.of(room("Спальня")),
                List.of(opening("Спальня", 900, 2100)),
                List.of(), List.of("висоти вікон відсутні")));

        var takeoff = surfaceService.run(PDF, null);

        // Аркуші для кімнат — лише обмірні/експлікація, БЕЗ плану розеток.
        verify(extractor).extractRooms(PDF, List.of(3, 5));
        // Електро-виклики не робились узагалі.
        verify(extractor, never()).extractPoints(any(), org.mockito.ArgumentMatchers.anyInt(),
                anyList(), anyList());
        verify(extractor, never()).extractLighting(any(), anyList());
        verify(extractor, never()).extractHeating(any(), anyList());

        assertThat(takeoff.surfaces().rooms()).hasSize(1);
        assertThat(takeoff.extraction().missing()).contains("висоти вікон відсутні");
    }

    @Test
    void electroFlowRunsPointsPerFloorAndSkipsRooms() {
        ElectroTakeoffService electroService =
                new ElectroTakeoffService(extractor, new ElectroTakeoffCalc());
        given(extractor.inventory(PDF, null)).willReturn(inventory(
                sheet(24, "sockets_switches", 1),
                sheet(25, "sockets_switches", 2),
                sheet(22, "light_switching", null),
                sheet(3, "measurement_plan", 1)));
        given(extractor.extractPoints(eq(PDF), org.mockito.ArgumentMatchers.anyInt(),
                anyList(), anyList()))
                .willReturn(new PointsResult(
                        List.of(point(PointType.SOCKET, 4)), List.of(), List.of()));
        given(extractor.extractLighting(eq(PDF), anyList()))
                .willReturn(new LightingResult(List.of(), List.of(), List.of(), List.of()));
        given(extractor.extractHeating(eq(PDF), anyList()))
                .willReturn(new HeatingResult(
                        new FloorHeating(false, FloorHeating.SystemType.UNKNOWN,
                                List.of(), false, null),
                        new PanelLocation(false, null),
                        List.of(), List.of(), List.of()));

        var takeoff = electroService.run(PDF, null);

        // По одному виклику точок на кожен поверх з планом розеток.
        verify(extractor).extractPoints(PDF, 1, List.of(24), List.of());
        verify(extractor).extractPoints(PDF, 2, List.of(25), List.of());
        // Кімнати/площі не викликались — маляр і електрик не платять одне за одного.
        verify(extractor, never()).extractRooms(any(), anyList());

        assertThat(takeoff.extraction().electricalPoints()).hasSize(2); // 2 поверхи × 1 запис
        assertThat(takeoff.takeoff().openQuestions())
                .anyMatch(q -> q.contains("щита")); // panel known:false донесено до результату
    }

    @Test
    void nonAlbumUploadIsRefusedBeforeExpensivePasses() {
        surfaceService = new SurfaceTakeoffService(extractor, new RoomSurfaceCalc());
        Meta notAlbum = new Meta(null, null, 0, null, "photos", false, null);
        given(extractor.inventory(PDF, null))
                .willReturn(new Inventory(notAlbum, List.of(), List.of()));

        assertThatThrownBy(() -> surfaceService.run(PDF, null))
                .isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("unrecognized");
        verify(extractor, never()).extractRooms(any(), anyList());
    }

    @Test
    void unreadableSheetsAreNotOfferedToExtractionPasses() {
        surfaceService = new SurfaceTakeoffService(extractor, new RoomSurfaceCalc());
        Sheet unreadable = new Sheet(7, null, "Обмірний план", "measurement_plan", 1,
                false, "фото під кутом, нечитабельне");
        given(extractor.inventory(PDF, null)).willReturn(inventory(
                unreadable, sheet(8, "measurement_plan", 2)));
        given(extractor.extractRooms(eq(PDF), anyList())).willReturn(
                new RoomsAndOpenings(List.of(), List.of(), List.of(), List.of()));

        surfaceService.run(PDF, null);

        verify(extractor).extractRooms(PDF, List.of(8)); // нечитабельний аркуш 7 відсіяно
    }

    // ---- fixtures --------------------------------------------------------------

    private static Inventory inventory(Sheet... sheets) {
        Meta meta = new Meta("Тест", null, 2, 160.0, "multi_page_pdf", true, null);
        return new Inventory(meta, List.of(sheets), List.<DataAvailability>of());
    }

    private static Sheet sheet(int index, String kind, Integer floor) {
        return new Sheet(index, null, kind, kind, floor, true, null);
    }

    private static Room room(String name) {
        return new Room(1, "1", name, "4000×3000", null, null, 12.0, 2800, null,
                Status.COUNTED, false, null);
    }

    private static Opening opening(String room, int w, int h) {
        return new Opening(1, room, null, "door_interior", w, h, null, true, null,
                Status.COUNTED, false, null);
    }

    private static ElectricalPoint point(PointType type, int qty) {
        return new ElectricalPoint(1, "Кімната", type, qty, 300, null,
                Status.COUNTED, false, null);
    }
}
