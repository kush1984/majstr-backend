package com.majstr.backend.repository;

import com.majstr.backend.entity.RegionalPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RegionalPriceRepository extends JpaRepository<RegionalPrice, UUID> {

    /** Rebuilt from scratch on every weekly run. */
    @Modifying
    @Query("DELETE FROM RegionalPrice")
    void deleteAllRows();

    /** Positions with an oblast median of their own, per region — the admin's «скільки даних». */
    @Query("SELECT r.region AS region, COUNT(r) AS positions FROM RegionalPrice r GROUP BY r.region")
    List<RegionCount> countByRegion();

    /** Level 1 of a price hint: this position's median in the master's oblast. */
    java.util.Optional<RegionalPrice> findFirstByRegionAndNameKeyAndItemTypeAndUnit(
            com.majstr.backend.entity.Region region, String nameKey,
            com.majstr.backend.entity.ItemType itemType, com.majstr.backend.entity.Unit unit);

    /** The newest row — when the weekly run last wrote. */
    java.util.Optional<RegionalPrice> findTopByOrderByComputedAtDesc();

    interface RegionCount {
        com.majstr.backend.entity.Region getRegion();
        long getPositions();
    }
}
