package com.majstr.backend.service.measurement;

import com.majstr.backend.config.AiFlowsProperties;
import com.majstr.backend.config.AnthropicProperties;
import com.majstr.backend.config.OpenAiProperties;
import com.majstr.backend.dto.ProjectTriageRequest;
import com.majstr.backend.dto.ProjectTriageResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import com.majstr.backend.service.ai.AiExtractors;
import com.majstr.backend.service.ai.AiInput;
import com.majstr.backend.service.ai.JsonExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Sorting a set's sheets by their titles, instead of by a keyword list we wrote.
 *
 * <p>What has to hold is that the answer is USED but never TRUSTED blindly: a kind outside the
 * agreed set, a sheet id nobody sent, a missing flag — each is a thing a model does occasionally, and
 * each would otherwise tick a row that does not exist or send a value the client cannot read.</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectTriageServiceTest {

    @Mock private FeatureGuard featureGuard;
    @Mock private ProjectService projectService;
    @Mock private JsonExtractor extractor;
    @Mock private UserRepository userRepository;

    private ProjectTriageService service;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AiExtractors extractors = new AiExtractors(new AiFlowsProperties(null, null, null),
                new AnthropicProperties("", "m", 1), new OpenAiProperties("", "m", 1, null), extractor);
        service = new ProjectTriageService(featureGuard, projectService, extractors, userRepository,
                JsonMapper.builder().build());
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(Plan.PRO).build()));
    }

    private static ProjectTriageRequest request(ProjectTriageRequest.Sheet... sheets) {
        return new ProjectTriageRequest(List.of(sheets));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tellsTheTwoVERSIONSofOnePlanApart() {
        // The Дубляни archive: two measure plans per floor, file names identical bar a leading digit,
        // and only the title says which is the flat that will exist. A keyword list cannot know this.
        String json = """
                {"sheets":[
                 {"id":"a","title":"Обмірний план приміщень 1 поверх","kind":"PLAN_MEASURE","floor":"1",
                  "version":"EXISTING","hasRoomTable":false,"hasDimensions":true,
                  "hasOpeningSizes":false,"worthReading":true,"note":"варіант до перепланування"},
                 {"id":"b","title":"Обмірний план приміщень після перепланування 1 поверх",
                  "kind":"PLAN_MEASURE","floor":"1","version":"AFTER","hasRoomTable":false,
                  "hasDimensions":true,"hasOpeningSizes":false,"worthReading":true,"note":""}]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectTriageResponse resp = service.triage(ownerId, objectId, request(
                new ProjectTriageRequest.Sheet("a", "1_обмірний план 1п.pdf", "Обмірний план приміщень 1 поверх"),
                new ProjectTriageRequest.Sheet("b", "7_обмірний план 1п.pdf", "…після перепланування…")));

        assertThat(resp.sheets()).hasSize(2);
        assertThat(resp.sheets().get(0).version()).isEqualTo("EXISTING");
        assertThat(resp.sheets().get(1).version()).isEqualTo("AFTER");
        assertThat(resp.sheets().get(1).floor()).isEqualTo("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRussianTitledSheetIsClassifiedLikeAnyOther() {
        // The case the keyword lists could not reach: not one of them held a Russian word, so a set
        // like this was never sent at all.
        String json = """
                {"sheets":[{"id":"1","title":"Экспликация помещений","kind":"ROOM_SCHEDULE",
                 "floor":"2","version":"UNKNOWN","hasRoomTable":true,"hasDimensions":false,
                 "hasOpeningSizes":false,"worthReading":true,"note":""}]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectTriageResponse resp = service.triage(ownerId, objectId, request(
                new ProjectTriageRequest.Sheet("1", "лист 4.pdf", "Экспликация помещений 2 этаж")));

        assertThat(resp.sheets().get(0).kind()).isEqualTo("ROOM_SCHEDULE");
        assertThat(resp.sheets().get(0).worthReading()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKindWeDoNotUnderstandBecomesOTHERratherThanTravellingOn() {
        String json = """
                {"sheets":[{"id":"1","title":"План стель","kind":"CEILING_PLAN","floor":"",
                 "version":"UNKNOWN","hasRoomTable":false,"hasDimensions":false,
                 "hasOpeningSizes":false,"worthReading":true,"note":""}]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectTriageResponse resp = service.triage(ownerId, objectId,
                request(new ProjectTriageRequest.Sheet("1", "p18.pdf", "ПЛАН СТЕЛЬ")));

        // The client has a fixed set of kinds; an unknown one would be untranslatable there.
        assertThat(resp.sheets().get(0).kind()).isEqualTo("OTHER");
        // …and the recommendation it came with is still honoured.
        assertThat(resp.sheets().get(0).worthReading()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSheetIdWeNeverSentIsDropped() {
        // A hallucinated row would tick a file that does not exist, or worse, shift the master's list.
        String json = """
                {"sheets":[
                 {"id":"1","title":"ОБМІРНИЙ ПЛАН","kind":"PLAN_MEASURE","floor":"","version":"UNKNOWN",
                  "hasRoomTable":true,"hasDimensions":true,"hasOpeningSizes":true,"worthReading":true,"note":""},
                 {"id":"99","title":"Сторінка, якої не існує","kind":"PLAN_MEASURE","floor":"",
                  "version":"UNKNOWN","hasRoomTable":false,"hasDimensions":false,
                  "hasOpeningSizes":false,"worthReading":true,"note":""}]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectTriageResponse resp = service.triage(ownerId, objectId,
                request(new ProjectTriageRequest.Sheet("1", "p3.pdf", "ОБМІРНИЙ ПЛАН")));

        assertThat(resp.sheets()).hasSize(1);
        assertThat(resp.sheets().get(0).id()).isEqualTo("1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSheetTheModelFORGOTisStillOfferedToTheMaster() {
        // «Return exactly one entry per sheet» is a request in a prompt, not a guarantee, and
        // dropping a row from a 44-item list is an ordinary thing for a model to do. A sheet with
        // no verdict is a sheet nobody reads — which is EXACTLY the failure this whole pass exists
        // to remove, and it would come back one layer higher.
        String json = """
                {"sheets":[{"id":"1","title":"ОБМІРНИЙ ПЛАН","kind":"PLAN_MEASURE","floor":"1",
                 "version":"AFTER","hasRoomTable":true,"hasDimensions":true,
                 "hasOpeningSizes":true,"worthReading":true,"note":""}]}""";
        given(extractor.requestJson(anyList(), anyString(), any(Map.class))).willReturn(json);

        ProjectTriageResponse resp = service.triage(ownerId, objectId, request(
                new ProjectTriageRequest.Sheet("1", "p3.pdf", "ОБМІРНИЙ ПЛАН"),
                new ProjectTriageRequest.Sheet("2", "p4.pdf", "Експлікація приміщень")));

        assertThat(resp.sheets()).extracting(ProjectTriageResponse.Sheet::id)
                .as("обидва аркуші мають повернутись, навіть якщо модель згадала один")
                .containsExactlyInAnyOrder("1", "2");
        ProjectTriageResponse.Sheet forgotten = resp.sheets().stream()
                .filter(s -> s.id().equals("2")).findFirst().orElseThrow();
        // Ticked by default, for the same reason the prompt says so: a sheet wrongly read costs one
        // call and is shown to the master anyway; a sheet wrongly skipped is invisible.
        assertThat(forgotten.worthReading()).isTrue();
        assertThat(forgotten.note()).contains("не вдалося розпізнати");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCallCarriesEVERYsheetAsTextWithItsIdAndBothEndsOfIt() {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class)))
                .willReturn("{\"sheets\":[]}");
        // A title block lands at the END of the extraction order as often as at the start, so a
        // sheet longer than the budget must keep both ends — cutting only the tail loses the title.
        String long_ = "START-OF-SHEET " + "3 250 970 2 430 ".repeat(400) + " ОБМІРНИЙ ПЛАН А-03";

        service.triage(ownerId, objectId, request(
                new ProjectTriageRequest.Sheet("a", "p1.pdf", "ТИТУЛЬНИЙ ЛИСТ"),
                new ProjectTriageRequest.Sheet("b", "p3.pdf", long_)));

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        String sent = ((AiInput.Text) content.getValue().get(0)).text();
        assertThat(sent).contains("id=a").contains("ТИТУЛЬНИЙ ЛИСТ")
                .contains("id=b").contains("START-OF-SHEET").contains("ОБМІРНИЙ ПЛАН А-03");
        // One text call for the whole set — the point of doing this before anything expensive.
        assertThat(content.getValue()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSheetWithNoTextLayerIsOfferedAsSuchRatherThanAsEmptiness() {
        given(extractor.requestJson(anyList(), anyString(), any(Map.class)))
                .willReturn("{\"sheets\":[]}");

        service.triage(ownerId, objectId,
                request(new ProjectTriageRequest.Sheet("a", "скан.pdf", "   ")));

        ArgumentCaptor<List<AiInput>> content = ArgumentCaptor.forClass(List.class);
        verify(extractor).requestJson(content.capture(), anyString(), any(Map.class));
        assertThat(((AiInput.Text) content.getValue().get(0)).text()).contains("(no text layer)");
    }
}
