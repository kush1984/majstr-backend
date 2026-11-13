package com.majstr.backend.repository;

import com.majstr.backend.entity.EstimateTileLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstimateTileLayoutRepository extends JpaRepository<EstimateTileLayout, UUID> {

    /** One estimate's layouts — the calculation reads them once. */
    List<EstimateTileLayout> findByEstimateId(UUID estimateId);

    Optional<EstimateTileLayout> findByEstimateItemId(UUID estimateItemId);

    /**
     * The line's row lock for the length of a save: two first saves of one layout (a double tap, an
     * outbox replay beside a live save) would both read «nothing stored», both insert, and the loser
     * would hit {@code ux_tile_layout_item} as a 500. Serialised on the line, the second one updates.
     */
    @Query(value = "SELECT id FROM estimate_items WHERE id = :itemId FOR UPDATE", nativeQuery = true)
    UUID lockItem(@Param("itemId") UUID itemId);

    @Modifying
    @Query("DELETE FROM EstimateTileLayout l WHERE l.estimateItemId = :itemId")
    int deleteByEstimateItemId(@Param("itemId") UUID itemId);
}
