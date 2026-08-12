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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Object measurements (Заміри), PRO-gated ({@code Feature.MEASUREMENTS}) and owner-scoped
 * (rooms/items belong to the caller's object) — TEMPORARILY also granted to FREE, see the comment
 * on {@code Plan.FREE} in {@link com.majstr.backend.feature.PlanConfig}. {@code result} is computed
 * server-side ({@link MeasurementCalc}); the payload is stored for re-editing. Owner-only — never
 * part of any client/portal/PDF response. Every mutating call returns the fresh tree so the PWA
 * updates totals in one round-trip.
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
        return addRoom(objectId, ownerId, req, null);
    }

    /**
     * Add a room, optionally with a CLIENT-PROVIDED id (offline authoring). The id makes the create
     * idempotent — a replayed offline create returns the tree unchanged instead of adding a second
     * room; an id that already belongs to a DIFFERENT object is rejected.
     */
    @Transactional
    public MeasurementsResponse addRoom(UUID objectId, UUID ownerId, MeasurementRoomRequest req, UUID requestedId) {
        requireMeasurements(objectId, ownerId);
        if (requestedId != null) {
            var existing = roomRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getProjectId().equals(objectId)) {
                    throw new AccessDeniedException("Measurement room belongs to a different object");
                }
                return buildTree(objectId); // idempotent replay
            }
        }
        roomRepository.save(MeasurementRoom.builder()
                .id(requestedId)
                .projectId(objectId)
                .name(req.name().trim())
                .floor(blankToNull(req.floor()))
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .build());
        return buildTree(objectId);
    }

    @Transactional
    public MeasurementsResponse updateRoom(UUID objectId, UUID roomId, UUID ownerId, MeasurementRoomRequest req) {
        requireMeasurements(objectId, ownerId);
        MeasurementRoom room = loadRoom(objectId, roomId);
        room.setName(req.name().trim());
        room.setFloor(blankToNull(req.floor()));
        if (req.sortOrder() != null) {
            room.setSortOrder(req.sortOrder());
        }
        return buildTree(objectId);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Transactional
    public MeasurementsResponse deleteRoom(UUID objectId, UUID roomId, UUID ownerId) {
        requireMeasurements(objectId, ownerId);
        // Idempotent: a replayed offline delete of an already-gone room is a no-op, not a 404.
        roomRepository.findByIdAndProjectId(roomId, objectId)
                .ifPresent(roomRepository::delete); // items cascade (FK)
        return buildTree(objectId);
    }

    // ---- items ----------------------------------------------------------------

    @Transactional
    public MeasurementsResponse addItem(UUID objectId, UUID roomId, UUID ownerId, MeasurementItemRequest req) {
        return addItem(objectId, roomId, ownerId, req, null);
    }

    /**
     * Add an element, optionally with a CLIENT-PROVIDED id (offline authoring) — idempotent on
     * replay; an id already living in a DIFFERENT room is rejected. The {@code result} is always
     * recomputed here: the client's optimistic number is a preview, never the source of truth.
     */
    @Transactional
    public MeasurementsResponse addItem(UUID objectId, UUID roomId, UUID ownerId,
                                        MeasurementItemRequest req, UUID requestedId) {
        requireMeasurements(objectId, ownerId);
        loadRoom(objectId, roomId); // authorize the room belongs to the object
        if (requestedId != null) {
            var existing = itemRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getRoomId().equals(roomId)) {
                    throw new AccessDeniedException("Measurement element belongs to a different room");
                }
                return buildTree(objectId); // idempotent replay
            }
        }
        BigDecimal result = calc.compute(req.type(), req.payload());
        itemRepository.save(MeasurementItem.builder()
                .id(requestedId)
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
        // Idempotent: a replayed offline delete of an already-gone element is a no-op, not a 404.
        itemRepository.findByIdAndRoomId(itemId, roomId).ifPresent(itemRepository::delete);
        return buildTree(objectId);
    }

    // ---- sketch import (create a whole reviewed sketch at once) ----------------

    /**
     * Create the master-confirmed sketch in one transaction: each room with its elements,
     * every {@code result} recomputed server-side from the payload (the LLM's/client's number
     * is never trusted). Returns the fresh tree. Gated + owner-scoped like every other write.
     */
    @Transactional
    public MeasurementsResponse createFromSketch(UUID objectId, UUID ownerId,
                                                 List<com.majstr.backend.dto.SketchCommitRequest.Room> rooms) {
        requireMeasurements(objectId, ownerId);
        int roomOrder = 0;
        for (var room : rooms) {
            MeasurementRoom saved = roomRepository.save(MeasurementRoom.builder()
                    .projectId(objectId)
                    .name(room.name().trim())
                    .sortOrder(roomOrder++)
                    .build());
            int itemOrder = 0;
            for (var item : room.items()) {
                itemRepository.save(MeasurementItem.builder()
                        .roomId(saved.getId())
                        .name(item.name().trim())
                        .type(item.type())
                        .unit(item.type().unit())
                        .result(calc.compute(item.type(), item.payload()))
                        .payload(item.payload().toString())
                        .sortOrder(itemOrder++)
                        .build());
            }
        }
        return buildTree(objectId);
    }

    /**
     * Create the master-confirmed project-documentation import in one transaction —
     * same contract as {@link #createFromSketch} plus the per-room floor label.
     */
    @Transactional
    public MeasurementsResponse createImported(UUID objectId, UUID ownerId,
                                               List<com.majstr.backend.dto.ProjectImportCommitRequest.Room> rooms) {
        requireMeasurements(objectId, ownerId);
        int roomOrder = 0;
        for (var room : rooms) {
            MeasurementRoom saved = roomRepository.save(MeasurementRoom.builder()
                    .projectId(objectId)
                    .name(room.name().trim())
                    .floor(blankToNull(room.floor()))
                    .sortOrder(roomOrder++)
                    .build());
            int itemOrder = 0;
            for (var item : room.items()) {
                itemRepository.save(MeasurementItem.builder()
                        .roomId(saved.getId())
                        .name(item.name().trim())
                        .type(item.type())
                        .unit(item.type().unit())
                        .result(calc.compute(item.type(), item.payload()))
                        .payload(item.payload().toString())
                        .sortOrder(itemOrder++)
                        .build());
            }
        }
        return buildTree(objectId);
    }

    // ---- tree + totals --------------------------------------------------------

    private MeasurementsResponse buildTree(UUID objectId) {
        List<MeasurementRoom> rooms = roomRepository.findByProjectIdOrderBySortOrderAscCreatedAtAsc(objectId);
        if (rooms.isEmpty()) {
            return new MeasurementsResponse(List.of(), ZERO, ZERO, ZERO);
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
        BigDecimal objPieces = ZERO;
        for (MeasurementRoom room : rooms) {
            List<MeasurementItem> items = byRoom.getOrDefault(room.getId(), List.of());
            BigDecimal area = ZERO;
            BigDecimal linear = ZERO;
            BigDecimal pieces = ZERO;
            List<MeasurementsResponse.Item> itemDtos = new java.util.ArrayList<>();
            for (MeasurementItem item : items) {
                // One bucket per unit — a count must never land in the m² figure, and cable
                // (unit M) is an electrical figure surfaced separately, not part of the area.
                switch (item.getUnit()) {
                    case M2 -> area = area.add(item.getResult());
                    case LINEAR_METER -> linear = linear.add(item.getResult());
                    case PIECE -> pieces = pieces.add(item.getResult());
                    default -> { } // M (cable) etc. — not an area/linear/piece total
                }
                itemDtos.add(new MeasurementsResponse.Item(
                        item.getId(), item.getName(), item.getType(), item.getUnit(),
                        item.getResult(), objectMapper.readTree(item.getPayload()), item.getSortOrder()));
            }
            objArea = objArea.add(area);
            objLinear = objLinear.add(linear);
            objPieces = objPieces.add(pieces);
            roomDtos.add(new MeasurementsResponse.Room(
                    room.getId(), room.getName(), room.getFloor(), room.getSortOrder(),
                    itemDtos, area, linear, pieces));
        }
        return new MeasurementsResponse(roomDtos, objArea, objLinear, objPieces);
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
