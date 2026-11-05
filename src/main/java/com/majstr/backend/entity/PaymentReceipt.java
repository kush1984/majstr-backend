package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A real, received payment against an object (V100), named by its own {@code label}. A row from
 * before the payment plan was removed (2026-11-05) may instead point at a {@link ProjectPayment}
 * stage, read-only, and carry the stage's name.
 */
@Entity
@Table(name = "payment_receipt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PaymentReceipt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Set only on a receipt recorded against an old plan stage; nothing writes it any more. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_payment_id")
    private ProjectPayment planPayment;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "received_at", nullable = false)
    private LocalDate receivedAt;

    /** The master's own name for the payment; null only on a receipt against an old plan stage. */
    @Column(name = "label", length = 255)
    private String label;

    /**
     * Money the client paid BACK for material the master laid out (V135), not payment for work.
     *
     * <p><b>Nothing in the object economy reads this.</b> «Отримано», the payments summary and every
     * stage's status count the receipt exactly as they always did — the standing constraint on this
     * area is that existing clients' figures must not shift. The ONE reader is the personal cash
     * screen, which keeps the money in the movement but takes it out of «Заробив»: counting a
     * reimbursement as earnings inflates a month by exactly the material.</p>
     *
     * <p>It is the small first half of the open «purpose on a payment» question
     * ({@code docs/open-questions.md}): only what the master tags himself is distinguishable, and a
     * payment entered from the OBJECT screen still carries no purpose at all.</p>
     */
    @Builder.Default
    @Column(name = "material_refund", nullable = false)
    private boolean materialRefund = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
