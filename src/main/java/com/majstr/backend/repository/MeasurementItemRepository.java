package com.majstr.backend.repository;

import com.majstr.backend.entity.MeasurementItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeasurementItemRepository extends JpaRepository<MeasurementItem, UUID> {

    /** Items for a set of rooms (the object's whole tree) in one query — no N+1. */
    List<MeasurementItem> findByRoomIdInOrderBySortOrderAscIdAsc(Collection<UUID> roomIds);

    Optional<MeasurementItem> findByIdAndRoomId(UUID id, UUID roomId);

    /** The selected elements that actually belong to the object (rooms of the project) —
     *  used to recompute a line's quantity server-side. Refs to deleted / foreign elements
     *  simply aren't returned. */
    List<MeasurementItem> findByRoomIdInAndIdIn(Collection<UUID> roomIds, Collection<UUID> ids);
}
