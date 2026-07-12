package com.majstr.backend.repository;

import com.majstr.backend.entity.MeasurementRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeasurementRoomRepository extends JpaRepository<MeasurementRoom, UUID> {

    List<MeasurementRoom> findByProjectIdOrderBySortOrderAscCreatedAtAsc(UUID projectId);

    Optional<MeasurementRoom> findByIdAndProjectId(UUID id, UUID projectId);
}
