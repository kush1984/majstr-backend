package com.majstr.backend.repository;

import java.time.Instant;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import com.majstr.backend.entity.InboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboxEntryRepository extends JpaRepository<InboxEntry, UUID> {

    Optional<InboxEntry> findByTelegramUpdateId(Long telegramUpdateId);

    Optional<InboxEntry> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<InboxEntry> findTop100ByOwnerIdAndStatusInOrderByCreatedAtDesc(UUID ownerId,
                                                                      Collection<InboxEntry.Status> statuses);

    long countByOwnerIdAndStatusIn(UUID ownerId, Collection<InboxEntry.Status> statuses);

    /** Voice notes this account ever sent — the FREE trial's count. */
    long countByOwnerIdAndVoiceSecondsNotNull(UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM InboxEntry e WHERE e.id = :id")
    Optional<InboxEntry> lockById(@Param("id") UUID id);

    /** «Спробувати ще раз»: only a FAILED entry, and only one caller wins it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE InboxEntry e SET e.status = com.majstr.backend.entity.InboxEntry.Status.PARSING, "
            + "e.updatedAt = :now WHERE e.id = :id AND e.ownerId = :ownerId "
            + "AND e.status = com.majstr.backend.entity.InboxEntry.Status.FAILED")
    int claimRetry(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("now") Instant now);

    /** A parse a deploy or a crash cut short: FAILED, so «Вхідні» shows it with «Спробувати ще раз». */
    @Modifying
    @Query("UPDATE InboxEntry e SET e.status = com.majstr.backend.entity.InboxEntry.Status.FAILED, "
            + "e.updatedAt = :now WHERE e.status = com.majstr.backend.entity.InboxEntry.Status.PARSING "
            + "AND e.updatedAt < :before")
    int failStale(@Param("before") Instant before, @Param("now") Instant now);
}
