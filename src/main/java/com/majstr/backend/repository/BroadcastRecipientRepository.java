package com.majstr.backend.repository;

import com.majstr.backend.entity.BroadcastRecipient;
import com.majstr.backend.entity.BroadcastSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BroadcastRecipientRepository extends JpaRepository<BroadcastRecipient, UUID> {

    List<BroadcastRecipient> findByBroadcastIdAndStatus(UUID broadcastId, BroadcastRecipient.Status status);

    Optional<BroadcastRecipient> findByUnsubscribeToken(String unsubscribeToken);

    /** When each master last got a letter of this segment — the «вже отримував» column. */
    @Query("SELECT r.userId AS userId, MAX(r.sentAt) AS sentAt FROM BroadcastRecipient r, Broadcast b "
            + "WHERE b.id = r.broadcastId AND b.segment = :segment "
            + "AND r.status = com.majstr.backend.entity.BroadcastRecipient.Status.SENT GROUP BY r.userId")
    List<UserSentAt> findLastSentBySegment(@Param("segment") BroadcastSegment segment);

    @Query("SELECT r.broadcastId AS broadcastId, r.status AS status, COUNT(r) AS cnt "
            + "FROM BroadcastRecipient r WHERE r.broadcastId IN :ids GROUP BY r.broadcastId, r.status")
    List<StatusCount> countByBroadcastIdIn(@Param("ids") Collection<UUID> ids);

    interface UserSentAt {
        UUID getUserId();
        Instant getSentAt();
    }

    interface StatusCount {
        UUID getBroadcastId();
        BroadcastRecipient.Status getStatus();
        long getCnt();
    }
}
