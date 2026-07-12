package com.majstr.backend.service.measurement;

import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementRoomRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.entity.MeasurementItem;
import com.majstr.backend.entity.MeasurementRoom;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.MeasurementItemRepository;
import com.majstr.backend.repository.MeasurementRoomRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Object measurements (Заміри), PRO-gated ({@code Feature.MEASUREMENTS} → PRO/TEAM; FREE
 * → 403 UPGRADE_REQUIRED) and owner-scoped (rooms/items belong to the caller's object).
 * {@code result} is computed server-side ({@link MeasurementCalc}); the payload is stored
 * for re-editing. Owner-only — never part of any client/portal/PDF response. Every mutating
 * call returns the fresh tree so the PWA updates totals in one round-trip.
 */
@Service
@RequiredArgsConstructor
public class MeasurementService {

    private static final int SCALE = 3;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private final MeasurementRoomRepository roomRepository;
    private final MeasurementItemRepository itemRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;
    private final MeasurementCalc calc;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public MeasurementsResponse tree(UUID objectId, UUID ownerId) {
        requireMeasurements(objectId, ownerId);
        return buildTree(objectId);
    }

    /**
     * Sum of the selected elements' results — the authoritative quantity for a line that
     * pulled from measurements. Owner-scoped by {@code projectId} (the caller already owns
     * the estimate/object). Each selected element must match the line's unit (else 400
     * {@code unit-mismatch}); refs to deleted / foreign elements are silently ignored. No
     * plan gate here — it runs inside the estimate save, and only PRO could have created
     * measurements in the first place.
     */
    @Transactional(readOnly = true)
    public BigDecimal sumForRefs(UUID projectId, List<UUID> refIds, Unit lineUnit) {
        if (refIds == null || refIds.isEmpty()) {
            return ZERO;
        }
        List<UUID> roomIds = roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(projectId)
                .stream().map(MeasurementRoom::getId).toList();
        if (roomIds.isEmpty()) {
            return ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (MeasurementItem item : itemRepository.findByRoomIdInAndIdIn(roomIds, refIds)) {
            if (item.getUnit() != lineUnit) {
                throw new com.majstr.backend.exception.MeasurementException("error.measurement.unit-mismatch");
            }
            sum = sum.add(item.getResult());
        }
        return sum.setScale(SCALE, java.math.RoundingMode.HALF_UP);
    }

    // ---- rooms ----------------------------------------------------------------

    @Transactional
    public MeasurementsResponse addRoom(UUID objectId, UUID ownerId, MeasurementRoomRequest req) {
        requireMeasurements(objectId, ownerId);
        roomRepository.save(MeasurementRoom.builder()
                .projectId(objectId)
                .name(req.name().trim())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build());
        return buildTree(objectId);
    }

    @Transactional
    public MeasurementsResponse updateRoom(UUID objectId, UUID roomId, UUID ownerId, MeasurementRoomRequest req) {
        requireMeasurements(objectId, ownerId);
        MeasurementRoom room = loadRoom(objectId, roomId);
        room.setName(req.name().trim());
        if (req.sortOrder() != null) {
            room.setSortOrder(req.sortOrder());
        }
        return buildTree(objectId);
    }

    @Transactional
    public MeasurementsResponse deleteRoom(UUID objectId, UUID roomId, UUID ownerId) {
        requireMeasurements(objectId, ownerId);
        roomRepository.delete(loadRoom(objectId, roomId)); // items cascade (FK)
        return buildTree(objectId);
    }

    // ---- items ----------------------------------------------------------------

    @Transactional
    public MeasurementsResponse addItem(UUID objectId, UUID roomId, UUID ownerId, MeasurementItemRequest req) {
        requireMeasurements(objectId, ownerId);
        loadRoom(objectId, roomId); // authorize the room belongs to the object
        BigDecimal result = calc.compute(req.type(), req.payload());
        itemRepository.save(MeasurementItem.builder()
                .roomId(roomId)
                .name(req.name().trim())
                .type(req.type())
                .unit(req.type().unit())
                .result(result)
                .payload(req.payload().toString())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build());
        return buildTree(objectId);
    }

    @Transactional
    public MeasurementsResponse updateItem(UUID objectId, UUID roomId, UUID itemId, UUID ownerId,
                                           MeasurementItemRequest req) {
        requireMeasurements(objectId, ownerId);
        loadRoom(objectId, roomId);
        MeasurementItem item = loadItem(roomId, itemId);
        item.setName(req.name().trim());
        item.setType(req.type());
        item.setUnit(req.type().unit());
        item.setResult(calc.compute(req.type(), req.payload()));
        item.setPayload(req.payload().toString());
        if (req.sortOrder() != null) {
            item.setSortOrder(req.sortOrder());
        }
        return buildTree(objectId);
    }

    @Transactional
    public MeasurementsResponse deleteItem(UUID objectId, UUID roomId, UUID itemId, UUID ownerId) {
        requireMeasurements(objectId, ownerId);
        loadRoom(objectId, roomId);
        itemRepository.delete(loadItem(roomId, itemId));
        return buildTree(objectId);
    }

    // ---- tree + totals --------------------------------------------------------

    private MeasurementsResponse buildTree(UUID objectId) {
        List<MeasurementRoom> rooms = roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId);
        if (rooms.isEmpty()) {
            return new MeasurementsResponse(List.of(), ZERO, ZERO);
        }
        List<UUID> roomIds = rooms.stream().map(MeasurementRoom::getId).toList();
        // One grouped query for the whole tree (no N+1).
        Map<UUID, List<MeasurementItem>> byRoom = new LinkedHashMap<>();
        for (MeasurementItem item : itemRepository.findByRoomIdInOrderBySortOrderAscIdAsc(roomIds)) {
            byRoom.computeIfAbsent(item.getRoomId(), k -> new java.util.ArrayList<>()).add(item);
        }

        List<MeasurementsResponse.Room> roomDtos = new java.util.ArrayList<>();
        BigDecimal objArea = ZERO;
        BigDecimal objLinear = ZERO;
        for (MeasurementRoom room : rooms) {
            List<MeasurementItem> items = byRoom.getOrDefault(room.getId(), List.of());
            BigDecimal area = ZERO;
            BigDecimal linear = ZERO;
            List<MeasurementsResponse.Item> itemDtos = new java.util.ArrayList<>();
            for (MeasurementItem item : items) {
                if (item.getUnit() == Unit.LINEAR_METER) {
                    linear = linear.add(item.getResult());
                } else {
                    area = area.add(item.getResult());
                }
                itemDtos.add(new MeasurementsResponse.Item(
                        item.getId(), item.getName(), item.getType(), item.getUnit(),
                        item.getResult(), objectMapper.readTree(item.getPayload()), item.getSortOrder()));
            }
            objArea = objArea.add(area);
            objLinear = objLinear.add(linear);
            roomDtos.add(new MeasurementsResponse.Room(
                    room.getId(), room.getName(), room.getSortOrder(), itemDtos, area, linear));
        }
        return new MeasurementsResponse(roomDtos, objArea, objLinear);
    }

    // ---- guards / loads -------------------------------------------------------

    /** Plan gate (PRO+) THEN ownership — a FREE master is refused before any object read. */
    private void requireMeasurements(UUID objectId, UUID ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.MEASUREMENTS);
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
    }

    private MeasurementRoom loadRoom(UUID objectId, UUID roomId) {
        return roomRepository.findByIdAndProjectId(roomId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Measurement room not found: " + roomId));
    }

    private MeasurementItem loadItem(UUID roomId, UUID itemId) {
        return itemRepository.findByIdAndRoomId(itemId, roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Measurement item not found: " + itemId));
    }
}
