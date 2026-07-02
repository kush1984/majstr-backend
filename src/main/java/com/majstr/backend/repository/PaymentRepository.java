package com.majstr.backend.repository;

import com.majstr.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** The webhook looks a payment up by the gateway invoice id (UNIQUE) and is
     *  idempotent on it — a repeated success never extends PRO twice. */
    Optional<Payment> findByInvoiceId(String invoiceId);
}
