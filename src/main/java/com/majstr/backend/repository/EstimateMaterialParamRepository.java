package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateMaterialParam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstimateMaterialParamRepository extends JpaRepository<EstimateMaterialParam, UUID> {

    /** One estimate's whole set — the calculation reads it once and asks nothing else. */
    List<EstimateMaterialParam> findByEstimateId(UUID estimateId);
}
