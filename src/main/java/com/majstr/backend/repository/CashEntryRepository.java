package com.majstr.backend.repository;

import com.majstr.backend.entity.CashEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashEntryRepository extends JpaRepository<CashEntry, UUID> {

    /**
     * One master's own rows for a period, newest first.
     *
     * <p>Ordered by the DAY and only then by the time inside it: {@code happened_on} is the
     * authoritative day and {@code happened_at} exists to break ties within it. {@code created_at}
     * is the final tie-break so the order is total — two rows entered in the same second on a
     * back-dated day would otherwise come back in whatever order the planner liked.</p>
     *
     * <p>Both bounds are INCLUSIVE: a period the master reads as «вересень» ends on the 30th, not
     * before it.</p>
     */
    @Query("""
            SELECT c FROM CashEntry c
            WHERE c.ownerId = :ownerId AND c.happenedOn BETWEEN :from AND :to
            ORDER BY c.happenedOn DESC, c.happenedAt DESC, c.createdAt DESC
            """)
    List<CashEntry> findByOwnerAndPeriod(@Param("ownerId") UUID ownerId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    /** Owner-scoped load: an id alone is never enough to reach someone else's money. */
    Optional<CashEntry> findByIdAndOwnerId(UUID id, UUID ownerId);
}
