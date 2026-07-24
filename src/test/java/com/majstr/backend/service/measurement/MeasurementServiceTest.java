package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementRoomRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.entity.MeasurementItem;
import com.majstr.backend.entity.MeasurementRoom;
import com.majstr.backend.entity.MeasurementType;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.MeasurementException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.repository.MeasurementItemRepository;
import com.majstr.backend.repository.MeasurementRoomRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MeasurementServiceTest {

    @Mock private MeasurementRoomRepository roomRepository;
    @Mock private MeasurementItemRepository itemRepository;
    @Mock private ProjectService projectService;
    @Mock private UserRepository userRepository;
    @Mock private FeatureGuard featureGuard;
    @Mock private MeasurementCalc calc;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private MeasurementService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MeasurementService(
                roomRepository, itemRepository, projectService, userRepository, featureGuard, calc, mapper);
    }

    private void givenUser(Plan plan) {
        given(userRepository.findById(ownerId))
                .willReturn(Optional.of(User.builder().id(ownerId).plan(plan).build()));
    }

    private MeasurementRoom room() {
        return MeasurementRoom.builder().id(roomId).projectId(objectId).name("Спальня").sortOrder(0).build();
    }

    private MeasurementItem item(MeasurementType type, Unit unit, String result) {
        return MeasurementItem.builder().id(UUID.randomUUID()).roomId(roomId).name("Стеля")
                .type(type).unit(unit).result(new BigDecimal(result)).payload("{}").sortOrder(0).build();
    }

    @Test
    void addRoom_isGatedForFree() {
        givenUser(Plan.FREE);
        willThrow(new FeatureNotAvailableException(Feature.MEASUREMENTS, Plan.FREE))
                .given(featureGuard).requireFeature(any(User.class), eq(Feature.MEASUREMENTS));

        assertThatThrownBy(() -> service.addRoom(objectId, ownerId, new MeasurementRoomRequest("Спальня", null, null)))
                .isInstanceOf(FeatureNotAvailableException.class);

        verify(roomRepository, never()).save(any());
    }

    @Test
    void addRoom_savesAndReturnsTree() {
        givenUser(Plan.PRO);
        given(roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId)).willReturn(List.of(room()));
        given(itemRepository.findByRoomIdInOrderBySortOrderAscIdAsc(anyList())).willReturn(List.of());

        MeasurementsResponse tree = service.addRoom(objectId, ownerId, new MeasurementRoomRequest("Спальня", "2", null));

        var saved = org.mockito.ArgumentCaptor.forClass(MeasurementRoom.class);
        verify(roomRepository).save(saved.capture());
        assertThat(saved.getValue().getFloor()).isEqualTo("2"); // the free-text floor label persists
        assertThat(tree.rooms()).hasSize(1);
        assertThat(tree.rooms().get(0).name()).isEqualTo("Спальня");
        assertThat(tree.areaTotal()).isEqualByComparingTo("0");
    }

    @Test
    void addItem_computesResultServerSideAndTotals() {
        givenUser(Plan.PRO);
        given(roomRepository.findByIdAndProjectId(roomId, objectId)).willReturn(Optional.of(room()));
        given(calc.compute(eq(MeasurementType.SURFACE), any(JsonNode.class))).willReturn(new BigDecimal("19.590"));
        given(roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId)).willReturn(List.of(room()));
        given(itemRepository.findByRoomIdInOrderBySortOrderAscIdAsc(anyList()))
                .willReturn(List.of(item(MeasurementType.SURFACE, Unit.M2, "19.59")));

        JsonNode payload = mapper.readTree("""
                {"segments":[{"l":5.31,"w":3.69}]}""");
        MeasurementsResponse tree = service.addItem(objectId, roomId, ownerId,
                new MeasurementItemRequest("Стеля", MeasurementType.SURFACE, payload, null));

        verify(itemRepository).save(any(MeasurementItem.class));
        assertThat(tree.rooms().get(0).items()).hasSize(1);
        assertThat(tree.rooms().get(0).items().get(0).result()).isEqualByComparingTo("19.59");
        assertThat(tree.areaTotal()).isEqualByComparingTo("19.59");
        assertThat(tree.linearTotal()).isEqualByComparingTo("0");
    }

    @Test
    void tree_splitsAreaAndLinearTotals() {
        givenUser(Plan.PRO);
        given(roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId)).willReturn(List.of(room()));
        given(itemRepository.findByRoomIdInOrderBySortOrderAscIdAsc(anyList())).willReturn(List.of(
                item(MeasurementType.SURFACE, Unit.M2, "10"),
                item(MeasurementType.LINEAR, Unit.LINEAR_METER, "5")));

        MeasurementsResponse tree = service.tree(objectId, ownerId);

        assertThat(tree.areaTotal()).isEqualByComparingTo("10");
        assertThat(tree.linearTotal()).isEqualByComparingTo("5");
        assertThat(tree.rooms().get(0).areaTotal()).isEqualByComparingTo("10");
        assertThat(tree.rooms().get(0).linearTotal()).isEqualByComparingTo("5");
    }

    @Test
    void sumForRefs_sumsMatchingUnits() {
        given(roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId)).willReturn(List.of(room()));
        given(itemRepository.findByRoomIdInAndIdIn(anyList(), anyList())).willReturn(List.of(
                item(MeasurementType.SURFACE, Unit.M2, "10"),
                item(MeasurementType.SURFACE, Unit.M2, "5")));

        BigDecimal sum = service.sumForRefs(objectId, List.of(UUID.randomUUID(), UUID.randomUUID()), Unit.M2);

        assertThat(sum).isEqualByComparingTo("15"); // deleted / foreign refs simply aren't returned
    }

    @Test
    void sumForRefs_rejectsUnitMismatch() {
        given(roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId)).willReturn(List.of(room()));
        given(itemRepository.findByRoomIdInAndIdIn(anyList(), anyList()))
                .willReturn(List.of(item(MeasurementType.LINEAR, Unit.LINEAR_METER, "5")));

        assertThatThrownBy(() -> service.sumForRefs(objectId, List.of(UUID.randomUUID()), Unit.M2))
                .isInstanceOf(MeasurementException.class);
    }

    @Test
    void sumForRefs_emptyIsZero() {
        assertThat(service.sumForRefs(objectId, List.of(), Unit.M2)).isEqualByComparingTo("0");
    }

    @Test
    void addItem_toUnknownRoom_404() {
        givenUser(Plan.PRO);
        given(roomRepository.findByIdAndProjectId(roomId, objectId)).willReturn(Optional.empty());

        JsonNode payload = mapper.readTree("{}");
        assertThatThrownBy(() -> service.addItem(objectId, roomId, ownerId,
                new MeasurementItemRequest("Стеля", MeasurementType.SURFACE, payload, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(itemRepository, never()).save(any());
    }
}
