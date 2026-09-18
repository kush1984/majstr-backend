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

    /** Every receipt of the object (planned + unplanned), one query — {@link
     *  com.majstr.backend.service.PaymentService#list}/{@code summary} group this in memory by
     *  {@code planPayment} id instead of running one query per stage. */
    List<PaymentReceipt> findByProjectIdOrderByReceivedAtAscCreatedAtAsc(UUID projectId);

    /** One stage's own history, for a single-row response (add/update a plan row). */
    List<PaymentReceipt> findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(UUID planPaymentId);

    /**
     * Every payment across ALL of one master's objects in a period (V135, «Мої гроші») — money IN,
     * the twin of {@code ObjectExpenseRepository.findByOwnerAndPeriod}.
     *
     * <p>The project is fetched because every row of the cash feed names the object it came from,
     * and the stage because a planned receipt has no label of its own — its purpose IS the stage's.
     * Both bounds INCLUSIVE.</p>
     */
    @Query("""
            SELECT r FROM PaymentReceipt r
            JOIN FETCH r.project p
            LEFT JOIN FETCH r.planPayment
            WHERE p.owner.id = :ownerId AND r.receivedAt BETWEEN :from AND :to
            ORDER BY r.receivedAt DESC, r.createdAt DESC
            """)
    List<PaymentReceipt> findByOwnerAndPeriod(@Param("ownerId") UUID ownerId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /** Σ received against one plan stage — used when resolving an overpayment against it. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r WHERE r.planPayment.id = :planPaymentId")
    BigDecimal sumByPlanPaymentId(@Param("planPaymentId") UUID planPaymentId);

    /** Σ every receipt of the object («Отримано грошей») — the FREE-visible works axis needs this
     *  without going through the PRO-gated {@code PaymentsSummaryResponse}. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r WHERE r.project.id = :projectId")
    BigDecimal sumByProjectId(@Param("projectId") UUID projectId);
}
