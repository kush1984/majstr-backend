package com.majstr.backend.service.measurement;

import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.ProjectImportCommitRequest;
import com.majstr.backend.dto.ProjectImportParseResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.importer.ClaudeEstimateExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Project-documentation import: the TEXT-FIRST routing (a PDF with a text layer
 * never goes to vision), the vision fallback (incl. the two-pass measure plan),
 * the sentinel discipline (0 → null + low confidence) and the commit delegation.
 * The Anthropic round-trip is mocked.
 */
@ExtendWith(MockitoExtension.class)
class ProjectImportServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private ProjectService projectService;
    @Mock private ClaudeEstimateExtractor extractor;
    @Mock private MeasurementService measurementService;
    @Mock private UserRepository userRepository;

    private ProjectImportService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();

    private static final String EMPTY_JSON = """
            {"floors":[],"coverings":[],"totals":{"totalAreaM2":0},"ceilingHeights":[],"warnings":[]}""";

    @BeforeEach
    void setUp() {
        service = new ProjectImportService(featureGuard, projectService, extractor,
                measurementService, userRepository, JsonMapper.builder().build());
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(Plan.PRO).build()));
    }

    // ---- routing ---------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void coveringsPdfWithTextLayer_goesTheCheapTextPath_neverVision() throws Exception {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(EMPTY_JSON);

        // A coverings spec is a plain table with no row-association risk — text is enough.
        service.parse(ownerId, objectId, ProjectImportService.Kind.COVERINGS,
                "специфікація покриттів.pdf", "application/pdf", textPdf());

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        // One text block carrying the extracted table — no "document"/"image" vision block.
        assertThat(content.getValue()).hasSize(1);
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Text.class);
        assertThat(((AiInput.Text) content.getValue().get(0)).text()).contains("Room schedule table");
    }

    @Test
    @SuppressWarnings("unchecked")
    void roomSchedulePdf_goesAsADocumentBlock_soRowsArentMisPairedByTextOrder() throws Exception {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(EMPTY_JSON);

        // Real designers' tables typeset a room's NAME away from its row, so the flattened
        // text order silently mis-pairs names with numbers («4 7,3 … Спальня дитяча»).
        service.parse(ownerId, objectId, ProjectImportService.Kind.ROOM_SCHEDULE,
                "експлікація 1п.pdf", "application/pdf", textPdf());

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Pdf.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scannedPdf_fallsBackToTheNativeDocumentBlock() throws Exception {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(EMPTY_JSON);

        service.parse(ownerId, objectId, ProjectImportService.Kind.COVERINGS,
                "скан.pdf", "application/pdf", blankPdf());

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Pdf.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleSheet_reportsWhichRoomNumbersItActuallyMarks() {
        // The two-floor archive: the SAME table is printed on both floors' sheets, so only
        // the numbers drawn on a sheet say which rooms are on that floor.
        String json = """
                {"floors":[{"floor":"1 поверх","roomsOnThisSheet":["1","3"],"rooms":[
                   {"number":"1","name":"Коридор","areaM2":26.5,"perimeterMm":0,"wallSegmentsMm":[],
                    "widthMm":0,"lengthMm":0,"cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":0,
                    "openings":[],"confidence":"high","note":""}]}],
                 "coverings":[],"totals":{"totalAreaM2":204},"ceilingHeights":[],"warnings":[]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectImportParseResponse resp = service.parse(ownerId, objectId,
                ProjectImportService.Kind.ROOM_SCHEDULE, "експлікація.jpg", "image/jpeg", new byte[]{1});

        assertThat(resp.floors().get(0).roomsOnThisSheet()).containsExactly("1", "3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void measurePlanPdf_goesAsOneDocumentBlock_evenWhenItHasATextLayer() throws Exception {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(EMPTY_JSON);

        // A real plan sheet carries BOTH the drawing and the rooms table (Belgradska p.3).
        // One combined call sees both; a text-only route would lose the geometry.
        service.parse(ownerId, objectId, ProjectImportService.Kind.PLAN_MEASURE,
                "обмірний план.pdf", "application/pdf", textPdf());

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Pdf.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void measurePlanPHOTO_stillRunsTwoPasses_inventoryThenDetails() {
        String inventory = """
                {"floors":[{"floor":"","roomsOnThisSheet":[],"rooms":[{"number":"4","name":"Спальня","areaM2":30,
                 "perimeterMm":0,"wallSegmentsMm":[],"widthMm":0,"lengthMm":0,"cutWidthMm":0,
                 "cutDepthMm":0,"ceilingHmm":0,"openings":[],"confidence":"high","note":""}]}],
                 "coverings":[],"totals":{"totalAreaM2":0},"ceilingHeights":[],"warnings":[]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class)))
                .willReturn(inventory, inventory);

        // A photo has no printed table to anchor a single call — the two passes stay.
        service.parse(ownerId, objectId, ProjectImportService.Kind.PLAN_MEASURE,
                "план.jpg", "image/jpeg", new byte[]{1, 2, 3});

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor, times(2)).requestJson(content.capture(), anyString(), any(Map.class));
        String pass1 = ((AiInput.Text) content.getAllValues().get(0).get(1)).text();
        String pass2 = ((AiInput.Text) content.getAllValues().get(1).get(1)).text();
        assertThat(pass1).contains("INVENTORY");
        // Pass 2 is anchored to the inventory's room list.
        assertThat(pass2).contains("№4 Спальня");
    }

    @Test
    @SuppressWarnings("unchecked")
    void planRoom_carriesGabaritsCutAndCeilingHeight() {
        // The real page-3 numbers: «Спальня 17,69 m²», 4990×3545, H=2850мм, Нпр/Нпд on a window
        // + an interior door (toFloor absent in JSON — a door is floor-reaching regardless).
        String json = """
                {"floors":[{"floor":"","roomsOnThisSheet":[],"rooms":[{"number":"2","name":"Спальня","areaM2":17.69,
                 "perimeterMm":0,"wallSegmentsMm":[],"widthMm":4990,"lengthMm":3545,
                 "cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":2850,
                 "openings":[{"kind":"вікно","wMm":1300,"hMm":1500,"sillMm":900,"toFloor":false,"note":""},
                             {"kind":"двері","wMm":900,"hMm":2100,"sillMm":0,"note":""}],
                 "confidence":"high","note":""}]}],
                 "coverings":[],"totals":{"totalAreaM2":163.91},"ceilingHeights":[],"warnings":[]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectImportParseResponse resp = service.parse(ownerId, objectId,
                ProjectImportService.Kind.PLAN_MEASURE, "план.jpg", "image/jpeg", new byte[]{1});

        ProjectImportParseResponse.Room room = resp.floors().get(0).rooms().get(0);
        assertThat(room.widthMm()).isEqualByComparingTo("4990");
        assertThat(room.lengthMm()).isEqualByComparingTo("3545");
        assertThat(room.ceilingHmm()).isEqualByComparingTo("2850"); // «H=», never «Нпр»
        assertThat(room.cutWidthMm()).isNull();                     // 0 sentinel → not an L-shape
        assertThat(room.openings()).hasSize(2);
        assertThat(room.openings().get(0).sillMm()).isEqualByComparingTo("900");
        assertThat(room.openings().get(0).toFloor()).isFalse();     // window on a sill
        assertThat(room.openings().get(1).toFloor()).isTrue();      // a door always reaches the floor
        assertThat(resp.totalAreaM2()).isEqualByComparingTo("163.91");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jpegPhoto_goesTheImageVisionPath() {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(EMPTY_JSON);

        service.parse(ownerId, objectId, ProjectImportService.Kind.COVERINGS,
                "фото специфікації.jpg", null, new byte[]{1, 2, 3});

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Image.class);
    }

    @Test
    void unsupportedFile_isRejectedBeforeAnyModelCall() {
        assertThatThrownBy(() -> service.parse(ownerId, objectId,
                ProjectImportService.Kind.ROOM_SCHEDULE, "архів.rar", null, new byte[]{1}))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.unsupported");
    }

    // ---- fragments: a second look, only when the first one failed ----------------

    /** One room, its area read from the table, and not a single dimension — the reported failure. */
    private static final String ROOMS_WITHOUT_GEOMETRY = """
            {"sheetTitle":"ОБМІРНИЙ ПЛАН","floors":[{"floor":"","roomsOnThisSheet":[],"rooms":[
             {"number":"4","name":"Дитяча","areaM2":16.46,"perimeterMm":0,"wallSegmentsMm":[],
              "widthMm":0,"lengthMm":0,"cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":0,
              "openings":[],"confidence":"medium","note":"","uncertain":["widthMm","lengthMm"]}]}],
             "coverings":[],"totals":{"totalAreaM2":0},"ceilingHeights":[],"warnings":[]}""";

    /** What a fragment sees once the same chains are 2.3x bigger. */
    private static final String GEOMETRY_FROM_FRAGMENT = """
            {"sheetTitle":"","floors":[{"floor":"","roomsOnThisSheet":[],"rooms":[
             {"number":"4","name":"Дитяча","areaM2":0,"perimeterMm":0,"wallSegmentsMm":[],
              "widthMm":4730,"lengthMm":3480,"cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":2850,
              "openings":[],"confidence":"high","note":"","uncertain":[]}]}],
             "coverings":[],"totals":{"totalAreaM2":0},"ceilingHeights":[],"warnings":[]}""";

    @Test
    @SuppressWarnings("unchecked")
    void aPlanWhoseChainsWereUnreadable_isReadAgainInFragments() throws Exception {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class)))
                .willReturn(ROOMS_WITHOUT_GEOMETRY, GEOMETRY_FROM_FRAGMENT, GEOMETRY_FROM_FRAGMENT,
                        GEOMETRY_FROM_FRAGMENT, GEOMETRY_FROM_FRAGMENT);

        ProjectImportParseResponse resp = service.parse(ownerId, objectId,
                ProjectImportService.Kind.PLAN_MEASURE, "обмірний план.pdf", "application/pdf",
                textPdf());

        // The whole page, then its four quarters.
        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor, times(5)).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(content.getAllValues().get(0).get(0)).isInstanceOf(AiInput.Pdf.class);
        assertThat(content.getAllValues().get(1).get(0)).isInstanceOf(AiInput.Image.class);
        // A fragment call carries the inventory read from the full page, so it knows what to look for.
        assertThat(((AiInput.Text) content.getAllValues().get(1).get(1)).text())
                .contains("№4 Дитяча").contains("FRAGMENT");

        ProjectImportParseResponse.Room room = resp.floors().get(0).rooms().get(0);
        assertThat(room.widthMm()).isEqualByComparingTo("4730");
        assertThat(room.lengthMm()).isEqualByComparingTo("3480");
        assertThat(room.ceilingHmm()).isEqualByComparingTo("2850");
        assertThat(room.areaM2()).isEqualByComparingTo("16.46");   // the table still owns the area
        assertThat(resp.sheetTitle()).isEqualTo("ОБМІРНИЙ ПЛАН");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPlanThatAlreadyReadItsChains_costsNoExtraCalls() throws Exception {
        // Fragments are four more vision calls on the same page. When the gabarits are already
        // there they were legible, and paying again buys nothing.
        String complete = ROOMS_WITHOUT_GEOMETRY
                .replace("\"widthMm\":0", "\"widthMm\":4730")
                .replace("\"lengthMm\":0", "\"lengthMm\":3480");
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(complete);

        service.parse(ownerId, objectId, ProjectImportService.Kind.PLAN_MEASURE,
                "обмірний план.pdf", "application/pdf", textPdf());

        verify(extractor, times(1)).requestJson(anyList(), anyString(), any(Map.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnnamedSheetIsStillRead_andSaysWhatItTurnedOutToBe() throws Exception {
        // A page whose stamp matched nothing used to be dropped before upload. On one real 19-sheet
        // set that was every page, and the import did nothing at all.
        given(extractor.requestJson(anyList(), anyString(), any(Map.class)))
                .willReturn(EMPTY_JSON.replace("{\"floors\"", "{\"sheetTitle\":\"ПЛАН ПІДЛОГ\",\"floors\""));

        ProjectImportParseResponse resp = service.parse(ownerId, objectId,
                ProjectImportService.Kind.UNKNOWN, "лист 7.pdf", "application/pdf", textPdf());

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        // Vision, not the flattened-text path: an unnamed sheet is far more likely a drawing.
        assertThat(content.getValue().get(0)).isInstanceOf(AiInput.Pdf.class);
        assertThat(((AiInput.Text) content.getValue().get(1)).text())
                .contains("A GUESS").contains("unclassified");
        assertThat(resp.sheetTitle()).isEqualTo("ПЛАН ПІДЛОГ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFigureReadButNotConfirmed_reachesTheMasterFlaggedInsteadOfBlank() {
        String json = """
                {"sheetTitle":"02_обмірний план","floors":[{"floor":"","roomsOnThisSheet":[],"rooms":[
                 {"number":"3","name":"Кухня","areaM2":0,"perimeterMm":0,"wallSegmentsMm":[],
                  "widthMm":5470,"lengthMm":2415,"cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":0,
                  "openings":[],"confidence":"medium","note":"площа не вказана",
                  "uncertain":["widthMm"]}]}],
                 "coverings":[],"totals":{"totalAreaM2":0},"ceilingHeights":[],"warnings":[]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectImportParseResponse.Room room = service.parse(ownerId, objectId,
                ProjectImportService.Kind.PLAN_MEASURE, "план.jpg", "image/jpeg", new byte[]{1})
                .floors().get(0).rooms().get(0);

        // The figure survives — a number the master can check beats an empty field.
        assertThat(room.widthMm()).isEqualByComparingTo("5470");
        assertThat(room.uncertain()).contains("widthMm");
        // A measure plan with no printed area is ordinary, not a broken row: the field is flagged,
        // and the row keeps its confidence because the gabarits it DID read can produce the area.
        assertThat(room.uncertain()).contains("areaM2");
        assertThat(room.confidence()).isNotEqualTo("low");
    }

    // ---- sentinel mapping -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void zeroSentinels_becomeNulls_andForceLowConfidence() {
        String json = """
                {"floors":[{"floor":"1 поверх","roomsOnThisSheet":[],"rooms":[
                   {"number":"7","name":"Ванна","areaM2":0,"perimeterMm":0,"wallSegmentsMm":[],
                    "widthMm":0,"lengthMm":0,"cutWidthMm":0,"cutDepthMm":0,"ceilingHmm":0,
                    "openings":[{"kind":"вікно","wMm":0,"hMm":1500,"sillMm":0,"note":""}],
                    "confidence":"high","note":""}]}],
                 "coverings":[{"name":"Плитка","kind":"підлога","qty":94.5,"unit":"M2"},
                              {"name":"Без кількості","kind":"стіни","qty":0,"unit":"M2"}],
                 "totals":{"totalAreaM2":204},
                 "ceilingHeights":[{"floor":"1","heightMm":0},{"floor":"2","heightMm":2700}],
                 "warnings":["без запасу на порізку"]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectImportParseResponse resp = service.parse(ownerId, objectId,
                ProjectImportService.Kind.ROOM_SCHEDULE, "photo.png", "image/png", new byte[]{1});

        ProjectImportParseResponse.Room room = resp.floors().get(0).rooms().get(0);
        assertThat(room.areaM2()).isNull();
        assertThat(room.perimeterMm()).isNull();
        assertThat(room.confidence()).isEqualTo("low"); // forced: no area = nothing to trust
        assertThat(room.openings()).isEmpty();          // an opening without both sizes can't subtract
        assertThat(resp.coverings()).hasSize(1);        // the qty-less line dropped
        assertThat(resp.totalAreaM2()).isEqualByComparingTo("204");
        assertThat(resp.ceilingHeightsMm()).containsOnlyKeys("2"); // 0-height dropped
        assertThat(resp.warnings()).containsExactly("без запасу на порізку");
    }

    // ---- commit -----------------------------------------------------------------

    @Test
    void commit_delegatesToTheSharedMeasurementCreate() {
        MeasurementsResponse tree = new MeasurementsResponse(List.of(),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        ProjectImportCommitRequest req = new ProjectImportCommitRequest(List.of(
                new ProjectImportCommitRequest.Room("Спальня", "1", List.of(
                        new MeasurementItemRequest("Підлога", com.majstr.backend.entity.MeasurementType.SURFACE,
                                JsonMapper.builder().build().readTree(
                                        "{\"unit\":\"M\",\"segments\":[{\"shape\":\"direct\",\"mode\":\"\",\"values\":{\"s\":30}}],\"openings\":[]}"),
                                0)))));
        given(measurementService.createImported(objectId, ownerId, req.rooms())).willReturn(tree);

        assertThat(service.commit(ownerId, objectId, req)).isSameAs(tree);
        verify(measurementService).createImported(objectId, ownerId, req.rooms());
    }

    // ---- fixtures ---------------------------------------------------------------

    /** A one-page PDF with a REAL text layer (≥150 chars), built with pdfbox. */
    private static byte[] textPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                cs.showText("Room schedule table: 1 Hall 12.5 m2; 2 Kitchen 14.2 m2; 3 Bedroom 18.7 m2; "
                        + "4 Bathroom 6.1 m2; 5 Corridor 8.4 m2; 6 Wardrobe 4.9 m2; 7 Balcony 3.2 m2; "
                        + "Total area 68.0 m2. Floor plan sheet 1 of 1, scale 1:50, all sizes in mm.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A one-page PDF with NO text layer — the "scan" case. */
    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
