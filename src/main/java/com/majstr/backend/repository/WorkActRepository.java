package com.majstr.backend.repository;

import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActKind;
import com.majstr.backend.entity.WorkActStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Owner-scoped load (share ops) — the act's {@code user_id} is the master. */
    java.util.Optional<WorkAct> findByIdAndUserId(UUID id, UUID userId);

    /** One open act per object: true if any DRAFT/SENT act already exists on the project. */
    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<WorkActStatus> statuses);

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
