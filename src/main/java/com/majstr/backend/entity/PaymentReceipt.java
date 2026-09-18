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
 * A real, received payment against an object (V100) — the FACT half of the payments PLAN/FACT
 * split. Each row closes a {@link ProjectPayment} stage partially or fully ({@code planPayment}
 * set), or stands alone with its own {@code label} ("Своє", no matching plan). A single plan stage
 * can be closed by several of these (2 000, then 3 000 = closed) — the thing {@code ProjectPayment}
 * itself could not express while it carried its own single {@code paidAmount}/{@code paidAt}.
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

    /** Null = unplanned ("Своє") — see {@code label}. Set NULL on delete of the plan row, so a
     *  real receipt is never destroyed just because its plan stage was removed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_payment_id")
    private ProjectPayment planPayment;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "received_at", nullable = false)
    private LocalDate receivedAt;

    /** Only meaningful when {@code planPayment} is null — the master's own name for an unplanned
     *  receipt, validated distinct from every plan stage's purpose so it can't be mistaken for one. */
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
