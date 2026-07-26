package com.majstr.backend.repository;

import com.majstr.backend.entity.Payment;
import com.majstr.backend.entity.PaymentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Atomically CLAIM a payment for granting: flips it to SUCCESS only if it is not
     * already, and reports how many rows that touched.
     *
     * <p>This is the concurrency guard for the webhook. Reading the status and then
     * writing it is check-then-act: monobank retrying a delivery while the first is
     * still in flight let BOTH transactions read PENDING and BOTH call {@code extendPro},
     * so one month's payment bought two. A conditional UPDATE makes the winner unambiguous
     * — the row lock serialises the second transaction, which then sees 0 rows affected
     * and grants nothing.</p>
     *
     * @return 1 for the caller that claimed it, 0 for every later delivery
     */
    // flush (so nothing pending is lost) but do NOT clear: clearing would detach the
    // caller's Payment and User, and the caller keeps using both. It re-syncs the two
    // touched fields on its in-memory copy instead.
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Payment p SET p.status = com.majstr.backend.entity.PaymentStatus.SUCCESS,
                                 p.paidAt = :paidAt
            WHERE p.id = :id AND p.status <> com.majstr.backend.entity.PaymentStatus.SUCCESS
            """)
    int claimForGrant(@Param("id") UUID id, @Param("paidAt") Instant paidAt);
}
