package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.SketchCommitRequest;
import com.majstr.backend.dto.SketchParseResponse;
import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Sketch import: the LLM JSON → review mapping (shapes, unit conversion, unreadable → blank +
 * low confidence) and the server-recompute on commit. The Anthropic round-trip is mocked; the
 * geometry (MeasurementCalc/Shapes) runs for real so the pinned areas are genuine.
 */
@ExtendWith(MockitoExtension.class)
class SketchImportServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private UserRepository userRepository;
    @Mock private ProjectService projectService;
    @Mock private JsonExtractor extractor;
    @Mock private MeasurementService measurementService;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private SketchImportService service;


    /** Every flow answered by one stub — these tests are about the service, not the routing. */
    private static AiExtractors allFlows(JsonExtractor extractor) {
        return new AiExtractors(new AiFlowsProperties(null, null, null),
                new AnthropicProperties("", "m", 1), new OpenAiProperties("", "m", 1, null), extractor);
    }

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SketchImportService(featureGuard, userRepository, projectService,
                allFlows(extractor), measurementService, new MeasurementCalc(mapper), mapper);
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(Plan.PRO).build()));
    }

    private static SketchImportService.Upload sheet(String filename) {
        return new SketchImportService.Upload(filename, "image/jpeg", new byte[]{1, 2, 3});
    }

    private SketchParseResponse parseWith(String json) {
        given(extractor.requestJson(anyList(), any(), any())).willReturn(json);
        return service.parse(ownerId, objectId, List.of(sheet("sketch.jpg")));
    }

    @Test
    void mapsARectangularCeilingInCentimetres() {
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Спальня","confidence":"high","items":[
                  {"type":"SURFACE","name":"Стеля","unit":"M2","confidence":"high","note":"",
                   "planes":[{"shape":"rect","mode":"","values":{"a":300,"b":250,"c":0,"d":0,"h":0}}],
                   "openings":[],
                   "partition":{"H":0,"W":0,"D":0,"faces":{"left":false,"right":false,"end":false,"top":false}},
                   "linear":{"H":0,"W":0,"sides":{"left":false,"right":false,"top":false,"bottom":false},"qty":1}}
                ]}],"unitGuess":"см","warnings":["масштаб не вказано"]}""");

        assertThat(resp.unitGuess()).isEqualTo("CM");
        assertThat(resp.warnings()).containsExactly("масштаб не вказано");
        assertThat(resp.rooms()).hasSize(1);
        SketchParseResponse.Room room = resp.rooms().get(0);
        assertThat(room.name()).isEqualTo("Спальня");
        assertThat(room.items()).hasSize(1);

        SketchParseResponse.Item item = room.items().get(0);
        assertThat(item.type()).isEqualTo(MeasurementType.SURFACE);
        assertThat(item.unit()).isEqualTo(Unit.M2);
        assertThat(item.confidence()).isEqualTo("high");
        assertThat(item.result()).isEqualByComparingTo("7.500"); // 300×250 cm = 7.5 m²

        Map<String, Object> payload = mapper.convertValue(item.payload(), Map.class);
        assertThat(payload.get("unit")).isEqualTo("CM");
        List<?> segments = (List<?>) payload.get("segments");
        Map<?, ?> seg = (Map<?, ?>) segments.get(0);
        assertThat(seg.get("shape")).isEqualTo("rect");
        // Unreadable (0) letters are omitted → the review field renders blank.
        // Bound the wildcard: containsOnlyKeys(K...) can't take Strings against a capture-of-?.
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) seg.get("values");
        assertThat(values).containsOnlyKeys("a", "b");
    }

    @Test
    void anLShapedFloorKeepsItsShapeInsteadOfBecomingTheGabaritsRectangle() {
        // The master's «Вітальня»: 9,50 × 6,00 overall with a 0,90 × 2,80 corner stepped in.
        // Read as a rect of the gabarits it was 57 m² — the corner's 2,52 m² billed as floor.
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Вітальня","confidence":"high","items":[
                  {"type":"SURFACE","name":"Підлога","unit":"M2","confidence":"high","note":"",
                   "planes":[{"shape":"lshape","mode":"","values":{"A":9.5,"B":6,"a":0.9,"b":2.8,"c":0,"d":0,"h":0}}],
                   "openings":[]}
                ]}],"unitGuess":"м","warnings":[]}""");

        SketchParseResponse.Item item = resp.rooms().get(0).items().get(0);
        assertThat(item.result()).isEqualByComparingTo("54.480"); // 9,5×6 − 0,9×2,8
        assertThat(item.confidence()).isEqualTo("high");
        Map<?, ?> seg = (Map<?, ?>) ((List<?>) mapper.convertValue(item.payload(), Map.class)
                .get("segments")).get(0);
        assertThat(seg.get("shape")).isEqualTo("lshape");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) seg.get("values");
        // Capital gabarits and the lowercase step both survive — the editor's four L fields.
        assertThat(values).containsOnlyKeys("A", "B", "a", "b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theModelIsOfferedTheLShapeInBothThePromptAndTheSchema() {
        // Without it the model had no way to say «Г-подібна» and drew the gabarits rectangle.
        parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[],"unitGuess":"м","warnings":[]}""");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(extractor).requestJson(anyList(), prompt.capture(), schema.capture());

        assertThat(prompt.getValue()).contains("\"lshape\"").contains("SIX walls");
        String schemaText = mapper.writeValueAsString(schema.getValue());
        assertThat(schemaText).contains("\"A\"").contains("\"B\"");
    }

    @Test
    void aGroupedWallsElementBecomesOneElementPerWallSoAnEstimateLineCanPickTwoOfThem() {
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Кухня","confidence":"high","items":[
                  {"type":"SURFACE","name":"Стіни","unit":"M2","confidence":"high","note":"",
                   "planes":[{"shape":"rect","mode":"","values":{"a":300,"b":250}},
                             {"shape":"rect","mode":"","values":{"a":400,"b":250}},
                             {"shape":"rect","mode":"","values":{"a":300,"b":250}}],
                   "openings":[{"w":100,"h":150}]}
                ]}],"unitGuess":"см","warnings":[]}""");

        List<SketchParseResponse.Item> items = resp.rooms().get(0).items();
        assertThat(items).extracting(SketchParseResponse.Item::name)
                .containsExactly("Стіна 1", "Стіна 2", "Стіна 3");
        // The group's window rides on the first wall: 3×2.5 − 1×1.5.
        assertThat(items.get(0).result()).isEqualByComparingTo("6.000");
        assertThat(items.get(1).result()).isEqualByComparingTo("10.000");
        assertThat(items.get(2).result()).isEqualByComparingTo("7.500");
    }

    @Test
    void oneWallMadeOfTwoFiguresStaysOneElement() {
        // A gable: a rectangle under a triangle is ONE wall — only the plural «Стіни» splits.
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Мансарда","confidence":"high","items":[
                  {"type":"SURFACE","name":"Стіна торцева","unit":"M2","confidence":"high","note":"",
                   "planes":[{"shape":"rect","mode":"","values":{"a":400,"b":100}},
                             {"shape":"tri","mode":"bh","values":{"b":400,"h":200}}],
                   "openings":[]}
                ]}],"unitGuess":"см","warnings":[]}""");

        List<SketchParseResponse.Item> items = resp.rooms().get(0).items();
        assertThat(items).extracting(SketchParseResponse.Item::name).containsExactly("Стіна торцева");
        assertThat(items.get(0).result()).isEqualByComparingTo("8.000");
    }

    @Test
    void unreadableSizeLeavesResultNullAndForcesLowConfidence() {
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"","confidence":"high","items":[
                  {"type":"SURFACE","name":"Стеля","unit":"M2","confidence":"high","note":"розмір нерозбірливий",
                   "planes":[{"shape":"rect","mode":"","values":{"a":300,"b":0,"c":0,"d":0,"h":0}}],
                   "openings":[],
                   "partition":{"H":0,"W":0,"D":0,"faces":{"left":false,"right":false,"end":false,"top":false}},
                   "linear":{"H":0,"W":0,"sides":{"left":false,"right":false,"top":false,"bottom":false},"qty":1}}
                ]}],"unitGuess":"см","warnings":[]}""");

        SketchParseResponse.Room room = resp.rooms().get(0);
        assertThat(room.name()).isEqualTo("Кімната 1"); // blank name → system-named
        SketchParseResponse.Item item = room.items().get(0);
        assertThat(item.result()).isNull();          // b unreadable → invalid → no area
        assertThat(item.confidence()).isEqualTo("low"); // forced low so the master checks it
        assertThat(item.note()).isEqualTo("розмір нерозбірливий");
    }

    @Test
    void dropsAnUnknownShapeAndConvertsPartitionToMetres() {
        // A partition read in centimetres: 250×120×30 cm, default faces (2 sides + end).
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Санвузол","confidence":"medium","items":[
                  {"type":"PARTITION","name":"Перегородка","unit":"M2","confidence":"medium","note":"",
                   "planes":[],"openings":[],
                   "partition":{"H":250,"W":120,"D":30,"faces":{"left":true,"right":true,"end":true,"top":false}},
                   "linear":{"H":0,"W":0,"sides":{"left":false,"right":false,"top":false,"bottom":false},"qty":1}}
                ]}],"unitGuess":"см","warnings":[]}""");

        SketchParseResponse.Item item = resp.rooms().get(0).items().get(0);
        assertThat(item.type()).isEqualTo(MeasurementType.PARTITION);
        // HW·2 + HD = 2.5·1.2·2 + 2.5·0.3 = 6.75 (dimensions converted cm → m)
        assertThat(item.result()).isEqualByComparingTo("6.750");
    }

    @Test
    void surfaceWithOnlyUnknownShapesHasNoResult() {
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[{"name":"Кухня","confidence":"low","items":[
                  {"type":"SURFACE","name":"Стеля","unit":"M2","confidence":"high","note":"",
                   "planes":[{"shape":"hexagon","mode":"","values":{"a":300,"b":250,"c":0,"d":0,"h":0}}],
                   "openings":[],
                   "partition":{"H":0,"W":0,"D":0,"faces":{"left":false,"right":false,"end":false,"top":false}},
                   "linear":{"H":0,"W":0,"sides":{"left":false,"right":false,"top":false,"bottom":false},"qty":1}}
                ]}],"unitGuess":"м","warnings":[]}""");

        SketchParseResponse.Item item = resp.rooms().get(0).items().get(0);
        JsonNode payload = item.payload();
        assertThat(payload.get("segments").size()).isZero(); // hexagon dropped
        assertThat(item.result()).isNull();
        assertThat(item.confidence()).isEqualTo("low");
    }

    @Test
    void parseIsGatedBySketchImportFeature() {
        parseWith("""
                {"sheetKind":"HAND_DRAWN","rooms":[],"unitGuess":"м","warnings":[]}""");
        verify(featureGuard).requireFeature(any(User.class), eq(Feature.SKETCH_IMPORT));
        verify(projectService).loadOwned(objectId, ownerId);
    }

    @Test
    void aPrintedPlanIsNamedRatherThanRead() {
        // The recogniser names a printed plan and stops — no rooms — because the import conveyor is
        // what reconciles printed areas against gabarits and guarantees each room its walls.
        SketchParseResponse resp = parseWith("""
                {"sheetKind":"PRINTED_PLAN","rooms":[],"unitGuess":"м",
                 "warnings":["технічний паспорт, читаю через імпорт проєкту"]}""");

        assertThat(resp.sheetKind()).isEqualTo("PRINTED_PLAN");
        assertThat(resp.rooms()).isEmpty();
    }

    @Test
    void aMissingSheetKindLeansTowardsTheImportConveyor() {
        // The default leans away from кроки on purpose. A model that omits the field must not
        // quietly get the sketch treatment: reading a printed plan as кроки is the exact failure
        // this field exists to stop, and it is the one that reached production.
        SketchParseResponse resp = parseWith("""
                {"rooms":[],"unitGuess":"м","warnings":[]}""");

        assertThat(resp.sheetKind()).isEqualTo("PRINTED_PLAN");
    }

    @Test
    void everySheetGoesIntoONEcallSoTheModelCanReadThemAgainstEachOther() {
        // A flat arrives as a page per floor plus its schedule. One call per sheet would give the
        // master a separate review each and no way for the model to carry a name from one to the
        // sizes on another, which is the whole reason a set is picked at once.
        given(extractor.requestJson(anyList(), any(), any()))
                .willReturn("""
                        {"sheetKind":"HAND_DRAWN","rooms":[],"unitGuess":"м","warnings":[]}""");

        service.parse(ownerId, objectId,
                List.of(sheet("floor-1.jpg"), sheet("floor-2.jpg"), sheet("schedule.jpg")));

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), any(), any());
        assertThat(content.getValue().stream().filter(AiInput.Image.class::isInstance)).hasSize(3);
        // Each image announced before it, so the model can tell them apart at all.
        assertThat(content.getValue().stream()
                .filter(AiInput.Text.class::isInstance)
                .map(i -> ((AiInput.Text) i).text()))
                .contains("SHEET 1 OF 3:", "SHEET 2 OF 3:", "SHEET 3 OF 3:");
    }

    @Test
    void aSingleSheetIsSentWithNoSheetLabelling() {
        given(extractor.requestJson(anyList(), any(), any()))
                .willReturn("""
                        {"sheetKind":"HAND_DRAWN","rooms":[],"unitGuess":"м","warnings":[]}""");

        service.parse(ownerId, objectId, List.of(sheet("plan.jpg")));

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), any(), any());
        assertThat(content.getValue().stream()
                .filter(AiInput.Text.class::isInstance)
                .map(i -> ((AiInput.Text) i).text()))
                .noneMatch(t -> t.startsWith("SHEET"));
    }

    @Test
    void aBatchOverTheSheetCapIsRefusedRatherThanPaidFor() {
        List<SketchImportService.Upload> tooMany =
                java.util.stream.IntStream.rangeClosed(1, 11)
                        .mapToObj(i -> sheet("p" + i + ".jpg"))
                        .toList();

        assertThatThrownBy(() -> service.parse(ownerId, objectId, tooMany))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.too-many-pages");
    }

    @Test
    void aNonImageAmongTheSheetsIsRefusedBeforeAnythingIsSent() {
        List<SketchImportService.Upload> mixed = List.of(
                sheet("plan.jpg"),
                new SketchImportService.Upload("notes.txt", "text/plain", new byte[]{1}));

        assertThatThrownBy(() -> service.parse(ownerId, objectId, mixed))
                .isInstanceOf(CatalogImportException.class)
                .hasMessage("error.import.unsupported");
    }

    @Test
    void commitDelegatesToMeasurementServiceAndIsGated() {
        MeasurementsResponse tree = new MeasurementsResponse(List.of(),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        var rooms = List.of(new SketchCommitRequest.Room("Спальня",
                List.of(new MeasurementItemRequest("Стеля", MeasurementType.SURFACE,
                        mapper.readTree("{\"unit\":\"M\",\"segments\":[{\"shape\":\"rect\",\"values\":{\"a\":3,\"b\":2.5}}],\"openings\":[]}"),
                        0))));
        given(measurementService.createFromSketch(eq(objectId), eq(ownerId), any())).willReturn(tree);

        MeasurementsResponse result = service.commit(ownerId, objectId, new SketchCommitRequest(rooms));

        assertThat(result).isSameAs(tree);
        verify(featureGuard).requireFeature(any(User.class), eq(Feature.SKETCH_IMPORT));
        verify(measurementService).createFromSketch(eq(objectId), eq(ownerId), any());
    }
}
