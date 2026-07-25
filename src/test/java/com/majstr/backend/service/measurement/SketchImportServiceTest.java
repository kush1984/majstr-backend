package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.SketchCommitRequest;
import com.majstr.backend.dto.SketchParseResponse;
import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.importer.ClaudeEstimateExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private ClaudeEstimateExtractor extractor;
    @Mock private MeasurementService measurementService;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private SketchImportService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SketchImportService(featureGuard, userRepository, projectService,
                extractor, measurementService, new MeasurementCalc(mapper), mapper);
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(Plan.PRO).build()));
    }

    private SketchParseResponse parseWith(String json) {
        given(extractor.requestJson(anyList(), any(), any())).willReturn(json);
        return service.parse(ownerId, objectId, "sketch.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    @Test
    void mapsARectangularCeilingInCentimetres() {
        SketchParseResponse resp = parseWith("""
                {"rooms":[{"name":"Спальня","confidence":"high","items":[
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
    void unreadableSizeLeavesResultNullAndForcesLowConfidence() {
        SketchParseResponse resp = parseWith("""
                {"rooms":[{"name":"","confidence":"high","items":[
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
                {"rooms":[{"name":"Санвузол","confidence":"medium","items":[
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
                {"rooms":[{"name":"Кухня","confidence":"low","items":[
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
                {"rooms":[],"unitGuess":"м","warnings":[]}""");
        verify(featureGuard).requireFeature(any(User.class), eq(Feature.SKETCH_IMPORT));
        verify(projectService).loadOwned(objectId, ownerId);
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
