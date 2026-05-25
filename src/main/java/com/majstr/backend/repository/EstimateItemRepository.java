package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstimateItemRepository extends JpaRepository<EstimateItem, UUID> {

    List<EstimateItem> findByEstimateIdOrderBySortOrderAscIdAsc(UUID estimateId);
}
