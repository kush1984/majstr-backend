package com.majstr.backend.repository;

import com.majstr.backend.entity.ProjectReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectReceiptRepository extends JpaRepository<ProjectReceipt, UUID> {

    /** The object's receipts in the ONE order they are shown in — newest paper first, undated on
     *  top, exactly like {@code WorkActReceiptRepository.findByWorkActIdNewestFirst}: a receipt with
     *  no date is one the master still has to look at, so it belongs where he lands. */
    @Query("""
            SELECT r FROM ProjectReceipt r
            WHERE r.projectId = :projectId
            ORDER BY r.issuedAt DESC NULLS FIRST, r.sortOrder ASC, r.createdAt ASC
            """)
    List<ProjectReceipt> findByProjectIdNewestFirst(@Param("projectId") UUID projectId);

    Optional<ProjectReceipt> findByIdAndProjectId(UUID id, UUID projectId);

    long countByProjectId(UUID projectId);

    @Query("SELECT COALESCE(MAX(r.sortOrder), -1) FROM ProjectReceipt r WHERE r.projectId = :projectId")
    int maxSortOrder(@Param("projectId") UUID projectId);

    /** Σ of what the client is expected to pay back — the receivable shown in the FREE-visible half
     *  of the object economy. Only the reimbursable rows: a receipt the master flipped to «моя
     *  витрата» is an {@code object_expenses} row and is counted there instead, never twice. */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.reimbursable = true
            """)
    BigDecimal sumReimbursable(@Param("projectId") UUID projectId);

    @Query("""
            SELECT COUNT(r) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.reimbursable = true
            """)
    long countReimbursable(@Param("projectId") UUID projectId);

    /** Is any receipt still unpriced? Amount 0 is a normal intermediate state (the photo is saved
     *  before it is read), so the screen says how many are still waiting for a number. */
    @Query("""
            SELECT COUNT(r) FROM ProjectReceipt r
            WHERE r.projectId = :projectId AND r.amount <= 0
            """)
    long countUnpriced(@Param("projectId") UUID projectId);
}
