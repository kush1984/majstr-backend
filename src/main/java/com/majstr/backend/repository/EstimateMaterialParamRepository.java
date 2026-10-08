package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateMaterialParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EstimateMaterialParamRepository extends JpaRepository<EstimateMaterialParam, UUID> {

    /** One estimate's whole set — the calculation reads it once and asks nothing else. */
    List<EstimateMaterialParam> findByEstimateId(UUID estimateId);

    /**
     * Take the estimate's row lock for the length of a params save (review B-112): two first saves
     * of one answer used to both read «nothing stored», both insert, and the loser hit V142's
     * partial unique index — a 500. Serialised on the estimate, the second one reads the first's row
     * and updates it.
     */
    @Query(
            value = "SELECT id FROM estimates WHERE id = :estimateId FOR UPDATE", nativeQuery = true)
    UUID lockEstimate(@Param("estimateId") UUID estimateId);

    /** Every answer given about one estimate line — dropped when the line stops being that line. */
    void deleteByEstimateItemId(UUID estimateItemId);
}
