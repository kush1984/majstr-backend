package com.majstr.backend.service.album;

import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.exception.AiExtractionException;
import com.majstr.backend.service.album.AlbumExtraction.Inventory;
import com.majstr.backend.service.album.AlbumExtraction.PointsResult;
import com.majstr.backend.service.album.AlbumExtraction.PointType;
import com.majstr.backend.service.album.AlbumExtraction.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests the wire shape and the JSON → record mapping without hitting the network:
 * the HTTP hop ({@code postForMap}) is stubbed with canned Anthropic responses. Locks the
 * Opus 4.7+ contract (adaptive thinking, no temperature/budget_tokens), the prompt-caching
 * layout (cache_control on the shared system block, document before the task text), and
 * the stop_reason failure modes (max_tokens/refusal → explicit errors, not garbage JSON).
 */
class ClaudeAlbumExtractorTest {

    private static final AnthropicProperties PROPS =
            new AnthropicProperties("test-key", "claude-opus-4-8", 8_000);
    private static final byte[] PDF = "fake-pdf".getBytes();

    /** Extractor with the HTTP hop replaced by a canned response (captures the body). */
    private static class StubbedExtractor extends ClaudeAlbumExtractor {
        Map<String, Object> lastBody;
        private final Map<String, Object> response;

        StubbedExtractor(Map<String, Object> response) {
            super(PROPS, JsonMapper.builder().build());
            this.response = response;
        }

        @Override
        Map<String, Object> postForMap(Map<String, Object> body) {
            this.lastBody = body;
            return response;
        }
    }

    private static Map<String, Object> okResponse(String json) {
        return Map.of(
                "stop_reason", "end_turn",
                "content", List.of(Map.of("type", "text", "text", json)));
    }

    // ---- wire shape -----------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void requestBodyFollowsOpusContractAndCachingLayout() {
        StubbedExtractor extractor = new StubbedExtractor(okResponse(POINTS_JSON));

        extractor.extractPoints(PDF, 1, List.of(24), List.of("Кухня"));
        Map<String, Object> body = extractor.lastBody;

        assertThat(body.get("model")).isEqualTo("claude-opus-4-8");
        // Opus 4.7+: adaptive thinking, і ЖОДНИХ temperature/top_p/budget_tokens (це 400).
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "adaptive"));
        assertThat(body).doesNotContainKeys("temperature", "top_p", "top_k");
        // Альбомні відповіді великі — конфіг на 8k не має їх обрізати.
        assertThat((int) body.get("max_tokens")).isGreaterThanOrEqualTo(16_000);

        // Structured outputs: схема стадії прикріплена.
        Map<String, Object> outputConfig = (Map<String, Object>) body.get("output_config");
        Map<String, Object> format = (Map<String, Object>) outputConfig.get("format");
        assertThat(format.get("type")).isEqualTo("json_schema");
        assertThat(format.get("schema")).isEqualTo(AlbumSchemas.POINTS);

        // Кеш-розкладка: system — масив блоків, cache_control на спільному блоці.
        List<Map<String, Object>> system = (List<Map<String, Object>>) body.get("system");
        assertThat(system).hasSize(2);
        assertThat(system.get(0).get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
        assertThat((String) system.get(0).get("text")).contains("ПРАВИЛА ЧЕСНОСТІ");
        assertThat((String) system.get(1).get("text")).contains("ПЕРЕХРЕСНА ЗВІРКА");

        // Документ першим у user-контенті, динамічне завдання — після нього.
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(content.get(0).get("type")).isEqualTo("document");
        assertThat(content.get(1).get("type")).isEqualTo("text");
        assertThat((String) content.get(1).get("text")).contains("поверху 1").contains("Кухня");
    }

    // ---- parsing --------------------------------------------------------------------

    private static final String POINTS_JSON = """
            {
              "electrical_points": [
                {"floor": 1, "room": "Кухня", "point_type": "socket", "qty": 4,
                 "height_mm": 1000, "purpose": null,
                 "status": "counted", "verify": false, "note": null},
                {"floor": 1, "room": "Кухня", "point_type": "power_outlet_220", "qty": 1,
                 "height_mm": 2000, "purpose": "витяжка",
                 "status": "counted", "verify": true, "note": "символ перекритий лінією"}
              ],
              "uncertain": ["Кухня: розподіл 6 розеток між h=1000/1500 неоднозначний"],
              "missing": []
            }
            """;

    @Test
    void parsesPointsIntoRecordsWithEnumsAndFlags() {
        PointsResult result = new StubbedExtractor(okResponse(POINTS_JSON))
                .extractPoints(PDF, 1, List.of(24), List.of("Кухня"));

        assertThat(result.electricalPoints()).hasSize(2);
        var socket = result.electricalPoints().get(0);
        assertThat(socket.pointType()).isEqualTo(PointType.SOCKET);
        assertThat(socket.qty()).isEqualTo(4);
        assertThat(socket.status()).isEqualTo(Status.COUNTED);
        assertThat(socket.verify()).isFalse();
        var hood = result.electricalPoints().get(1);
        assertThat(hood.pointType()).isEqualTo(PointType.POWER_OUTLET_220);
        assertThat(hood.purpose()).isEqualTo("витяжка");
        assertThat(hood.verify()).isTrue();
        assertThat(result.uncertain()).hasSize(1);
    }

    @Test
    void parsesInventoryWithNullableFields() {
        String json = """
                {
                  "meta": {"project_name": null, "address": "вул. Тестова, 1", "floors": 2,
                           "total_area_m2": 204.0, "input_kind": "sheet_files",
                           "is_design_album": true},
                  "sheets": [
                    {"index": 1, "source_file": "7_обмірний план 1п.pdf",
                     "title": "Обмірний план приміщень після перепланування",
                     "kind": "measurement_plan_after_remodel", "floor": 1,
                     "readable": true, "note": "заголовок ≠ імені файлу"}
                  ],
                  "data_availability": [
                    {"data_kind": "electrical_point_counts", "status": "manual_count_needed",
                     "source_sheets": [24, 25], "note": "таблиць немає, лише символи"}
                  ]
                }
                """;

        Inventory inventory = new StubbedExtractor(okResponse(json)).inventory(PDF, null);

        assertThat(inventory.meta().projectName()).isNull();
        assertThat(inventory.meta().totalAreaM2()).isEqualTo(204.0);
        assertThat(inventory.meta().isDesignAlbum()).isTrue();
        assertThat(inventory.sheets().get(0).kind())
                .isEqualTo("measurement_plan_after_remodel");
        assertThat(inventory.dataAvailability().get(0).status())
                .isEqualTo("manual_count_needed");
    }

    // ---- failure modes -----------------------------------------------------------------

    @Test
    void truncatedResponseIsAnExplicitErrorNotGarbageJson() {
        StubbedExtractor extractor = new StubbedExtractor(Map.of(
                "stop_reason", "max_tokens",
                "content", List.of(Map.of("type", "text", "text", "{\"electrical_"))));

        assertThatThrownBy(() -> extractor.extractPoints(PDF, 1, List.of(24), List.of()))
                .isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void refusalIsAnExplicitError() {
        StubbedExtractor extractor = new StubbedExtractor(Map.of(
                "stop_reason", "refusal", "content", List.of()));

        assertThatThrownBy(() -> extractor.inventory(PDF, null))
                .isInstanceOf(AiExtractionException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void unconfiguredKeyFailsFastWithoutNetwork() {
        ClaudeAlbumExtractor extractor = new ClaudeAlbumExtractor(
                new AnthropicProperties("", "claude-opus-4-8", 8_000),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> extractor.inventory(PDF, null))
                .isInstanceOf(AiExtractionException.class);
    }

    @Test
    void retriesTransientStatusesButNotPermanentOnes() {
        assertThat(ClaudeAlbumExtractor.isTransient(429)).isTrue();
        assertThat(ClaudeAlbumExtractor.isTransient(529)).isTrue();
        assertThat(ClaudeAlbumExtractor.isTransient(500)).isTrue();
        assertThat(ClaudeAlbumExtractor.isTransient(400)).isFalse();
        assertThat(ClaudeAlbumExtractor.isTransient(401)).isFalse();
        assertThat(ClaudeAlbumExtractor.isTransient(413)).isFalse();
    }
}
