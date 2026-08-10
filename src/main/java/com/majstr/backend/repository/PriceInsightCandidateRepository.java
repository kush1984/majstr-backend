package com.majstr.backend.repository;

import com.majstr.backend.entity.CatalogInsightKind;
import com.majstr.backend.entity.PriceInsightCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PriceInsightCandidateRepository extends JpaRepository<PriceInsightCandidate, UUID> {

    List<PriceInsightCandidate> findByKindOrderByMastersCountDescSampleNameAsc(CatalogInsightKind kind);

    /** The weekly refresh replaces the whole queue for a kind rather than diffing it — a
     *  position that no longer clears the bar (N dropped below 3, or a master's outlier moved
     *  the trim) must disappear, not linger from a stale row nothing deletes it. */
    @Modifying
    @Query("DELETE FROM PriceInsightCandidate c WHERE c.kind = :kind")
    void deleteAllByKind(CatalogInsightKind kind);

    /**
     * Session/transaction-scoped guard for {@code PriceInsightRefreshJob} (single-node today,
     * but cheap to make real rather than a comment). {@code _xact_} releases automatically at
     * commit/rollback — no matching unlock call needed, and no risk of a stuck lock outliving
     * the pooled connection it ran on.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(CAST(:key AS bigint))", nativeQuery = true)
    boolean tryAdvisoryXactLock(long key);
}
