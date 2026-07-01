package com.majstr.backend.repository;

import com.majstr.backend.dto.UpgradeLead;
import com.majstr.backend.entity.UpgradeEvent;
import com.majstr.backend.entity.UpgradeEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UpgradeEventRepository extends JpaRepository<UpgradeEvent, UUID> {

    long countByType(UpgradeEventType type);

    /** Distinct users for a type — demand WIDTH (clickers) / warm-lead count. */
    @Query("SELECT COUNT(DISTINCT e.userId) FROM UpgradeEvent e WHERE e.type = :type")
    long countDistinctUsersByType(UpgradeEventType type);

    /** Click intensity by trigger — which ceiling drives upgrades. One grouped query. */
    @Query("""
            SELECT e.triggerSource AS triggerSource, COUNT(e) AS total
            FROM UpgradeEvent e
            WHERE e.type = com.majstr.backend.entity.UpgradeEventType.CLICK
            GROUP BY e.triggerSource
            """)
    List<TriggerCount> countClicksByTrigger();

    /** Warm leads: each INTEREST submission with the user's contact, newest first. */
    @Query("""
            SELECT new com.majstr.backend.dto.UpgradeLead(u.id, u.email, u.fullName, e.reason, e.createdAt)
            FROM UpgradeEvent e, com.majstr.backend.entity.User u
            WHERE u.id = e.userId AND e.type = com.majstr.backend.entity.UpgradeEventType.INTEREST
            ORDER BY e.createdAt DESC
            """)
    List<UpgradeLead> findInterestLeads();

    // ---- per-user admin card --------------------------------------------------
    long countByUserIdAndType(UUID userId, UpgradeEventType type);

    Optional<UpgradeEvent> findFirstByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, UpgradeEventType type);

    /** Projection for the by-trigger breakdown. */
    interface TriggerCount {
        String getTriggerSource();
        long getTotal();
    }
}
