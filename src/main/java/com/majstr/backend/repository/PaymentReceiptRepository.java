package com.majstr.backend.repository;

import com.majstr.backend.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, UUID> {

    Optional<PaymentReceipt> findByIdAndProjectId(UUID id, UUID projectId);

    /** Every receipt of the object, oldest first — the payments summary. */
    List<PaymentReceipt> findByProjectIdOrderByReceivedAtAscCreatedAtAsc(UUID projectId);

    /**
     * Every payment across ALL of one master's objects in a period (V135, «Мої гроші») — money IN,
     * the twin of {@code ObjectExpenseRepository.findByOwnerAndPeriod}.
     *
     * <p>The project is fetched because every row of the cash feed names the object it came from,
     * and the old plan stage because a receipt recorded against one has no label of its own.
     * Both bounds INCLUSIVE.</p>
     */
    @Query("""
            SELECT r FROM PaymentReceipt r
            JOIN FETCH r.project p
            LEFT JOIN FETCH r.planPayment
            WHERE p.owner.id = :ownerId AND r.receivedAt BETWEEN :from AND :to
              AND (:projectId IS NULL OR p.id = :projectId)
              AND (:projectId IS NOT NULL OR p.sample = false)
            ORDER BY r.receivedAt DESC, r.createdAt DESC
            """)
    List<PaymentReceipt> findByOwnerAndPeriod(@Param("ownerId") UUID ownerId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to,
                                              @Param("projectId") UUID projectId);

    /** Σ every receipt of the object («Отримано грошей») — the FREE-visible works axis needs this
     *  without going through the PRO-gated {@code PaymentsSummaryResponse}. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r WHERE r.project.id = :projectId")
    BigDecimal sumByProjectId(@Param("projectId") UUID projectId);

    /** Σ of the object's receipts ticked «повернення за матеріал» (review B-65). Deliberately NOT
     *  subtracted from {@link #sumByProjectId}: «Отримано» means money that arrived, the refund
     *  included. What a refund may not do is pay off WORK — see {@code MaterialRefundSplit}. */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r
            WHERE r.project.id = :projectId AND r.materialRefund = true
            """)
    BigDecimal sumMaterialRefunds(@Param("projectId") UUID projectId);

    /** {@link #sumMaterialRefunds} for every object of one master at once, as
     *  {@code [projectId, sum]} rows — the other half of the per-object material balance. */
    @Query("""
            SELECT r.project.id, COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r
            WHERE r.project.owner.id = :ownerId AND r.materialRefund = true
              AND (:projectId IS NULL OR r.project.id = :projectId)
              AND (:projectId IS NOT NULL OR r.project.sample = false)
            GROUP BY r.project.id
            """)
    List<Object[]> sumMaterialRefundsByOwner(@Param("ownerId") UUID ownerId,
                                             @Param("projectId") UUID projectId);

    /** Whether any money was ever received on the object — the delete guard (review B-70). */
    boolean existsByProjectId(UUID projectId);
}
