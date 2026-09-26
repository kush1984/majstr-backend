package com.majstr.backend.repository;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkActRepository extends JpaRepository<WorkAct, UUID> {

    /** Acts of an object, newest first (by document date, then creation). */
    List<WorkAct> findByProjectIdOrderByIssuedAtDescCreatedAtDesc(UUID projectId);

    /**
     * Whether a SIGNED act created this estimate as its ADDENDUM rollup (B-58). An ADDENDUM holds
     * the act's off-estimate works, so no act line ever points AT it — {@code
     * WorkActItemRepository#existsSignedLineForEstimate} is structurally blind to it, and asking it
     * about an ADDENDUM always answered «no acts», which is how the rollup could be reopened,
     * duplicated and deleted while its money stayed inside «Прийнято актами».
     */
    boolean existsByAddendumEstimateIdAndStatus(UUID addendumEstimateId, WorkActStatus status);

    /**
     * The act row, locked {@code FOR UPDATE} — the door EVERY write to the act, its lines or its
     * receipts goes through, and the one both sign paths take first (review B-60).
     *
     * <p>Without it the status check and the write sat in different transactions, or in none at
     * all: {@code WorkActReceiptCreator} asked «not signed?» in a read-only transaction, spent
     * seconds uploading a photo, then inserted in a fresh one. A batch of five receipts uploading
     * while the client taps «Підписати» after the second is not a contrived race — it is the
     * ordinary shape of the feature. Photos 3-5 then land on a SIGNED act: unpriced and now
     * unpriceable (every receipt write is behind {@code requireNotSigned}), outside the
     * {@code doc_hash}, absent from the ADDENDUM — yet {@code sumSignedActReceipts} counts them,
     * so «Прийнято актами» passes «За договором» by the whole amount and «До сплати» disagrees
     * with the PDF the client is holding.
     *
     * <p>Serializing on the act row is what makes the check meaningful: either the write commits
     * first and the signature freezes a document that includes it, or the signature commits first
     * and the write is refused. Take this lock BEFORE any other row (the ADDENDUM estimate, the
     * object receipts) so both sign paths and every writer acquire in the same order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM WorkAct a WHERE a.id = :id")
    java.util.Optional<WorkAct> findByIdForUpdate(@Param("id") UUID id);

    /** Owner-scoped load (share ops) — the act's {@code user_id} is the master. */
    java.util.Optional<WorkAct> findByIdAndUserId(UUID id, UUID userId);

    /** One open act per object: true if any DRAFT/SENT act already exists on the project. */
    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<WorkActStatus> statuses);

    /** Same, excluding the act being transitioned — the REJECTED→DRAFT guard (review fix): another
     *  act may have been opened while this one sat rejected. */
    boolean existsByProjectIdAndStatusInAndIdNot(UUID projectId, Collection<WorkActStatus> statuses, UUID id);

    /** A FINAL act (any status) blocks creating another act on the object. */
    boolean existsByProjectIdAndKind(UUID projectId, WorkActKind kind);

    /** Whether the object has a SIGNED act OTHER than the given one — the gate for the «ДОВІДКОВО»
     *  cumulative reference block (it only makes sense from the second act onward). */
    boolean existsByProjectIdAndStatusAndIdNot(UUID projectId, WorkActStatus status, UUID id);

    /**
     * The highest running number this master has used so far (0 if none) — numbering is CONTINUOUS
     * per master, never reset per year, so the display string stays unique under UNIQUE(user_id,
     * number) for both PLAIN («7») and WITH_YEAR («7/2026») formats. The leading integer is parsed
     * out of the stored number; the next act is {@code max + 1} (gaps from deleted drafts are fine).
     */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(substring(number from '^[0-9]+') AS integer)), 0)
            FROM work_act WHERE user_id = :userId
            """, nativeQuery = true)
    int maxNumberSeqForUser(@Param("userId") UUID userId);
}
