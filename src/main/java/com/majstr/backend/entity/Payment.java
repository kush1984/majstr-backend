package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import java.util.UUID;

/**
 * One purchase attempt through a payment gateway. Immutable-ish record of a
 * subscription payment: created PENDING at checkout, flipped to SUCCESS (and PRO
 * granted) only by a verified provider webhook. {@code invoiceId} is the gateway's
 * id and is UNIQUE — the webhook is idempotent on it (a repeated success never
 * extends PRO twice). Plain {@code userId} (no association) mirrors
 * {@link UpgradeEvent}.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    /** Gateway invoice id (monobank). UNIQUE — the webhook is idempotent on it. */
    @Column(name = "invoice_id", length = 100, unique = true)
    private String invoiceId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** ISO 4217 numeric currency (980 = UAH). */
    @Column(name = "ccy", nullable = false)
    private int ccy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    /** Plan this payment buys, and for how many days (applied on success). */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20)
    private Plan plan;

    @Column(name = "days", nullable = false)
    private int days;

    /** Subscription period this payment buys (MONTH | HALF_YEAR | YEAR). Drives the
     *  server-side amount/days; also read back so auto-renew recharges the same
     *  period. */
    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 20)
    @Builder.Default
    private BillingPeriod period = BillingPeriod.MONTH;

    /** Manual checkout vs scheduled auto-renew token charge. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    @Builder.Default
    private PaymentKind kind = PaymentKind.CHECKOUT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

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
