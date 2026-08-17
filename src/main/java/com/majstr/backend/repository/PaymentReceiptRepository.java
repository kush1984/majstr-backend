package com.majstr.backend.repository;

import com.majstr.backend.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    /** Σ received against one plan stage — used when resolving an overpayment against it. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r WHERE r.planPayment.id = :planPaymentId")
    BigDecimal sumByPlanPaymentId(@Param("planPaymentId") UUID planPaymentId);

    /** Σ every receipt of the object («Отримано грошей») — the FREE-visible works axis needs this
     *  without going through the PRO-gated {@code PaymentsSummaryResponse}. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM PaymentReceipt r WHERE r.project.id = :projectId")
    BigDecimal sumByProjectId(@Param("projectId") UUID projectId);
}
