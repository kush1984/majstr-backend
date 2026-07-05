package com.majstr.backend.repository;

import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The webhook looks a payment up by the gateway invoice id (UNIQUE) and is
     *  idempotent on it — a repeated success never extends PRO twice. */
    Optional<Payment> findByInvoiceId(String invoiceId);

    /** AUTO_RENEW attempts for a user in the current retry window (created after a
     *  cutoff) — the scheduler reads these to avoid double-charging and to decide
     *  retry vs give-up. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.userId = :userId AND p.kind = com.majstr.backend.entity.PaymentKind.AUTO_RENEW
              AND p.createdAt >= :after
            ORDER BY p.createdAt DESC
            """)
    List<Payment> findAutoRenewSince(@Param("userId") UUID userId, @Param("after") Instant after);

    /** The most recent payment of a kind for a user — the admin's "last auto-charge". */
    Optional<Payment> findFirstByUserIdAndKindOrderByCreatedAtDesc(UUID userId, PaymentKind kind);
}
