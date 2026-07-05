package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record of a master→master referral reward: the referrer earned
 * {@code grantedDays} of PRO because the referred master made their first payment.
 *
 * <p>{@code referredUserId} is UNIQUE — the guarantee that one invited master ever
 * triggers exactly one reward (a retried webhook or a second payment can't
 * double-grant). {@code grantedDays} is 0 when the referrer already had an
 * admin-granted dateless PRO (nothing to extend — see open-questions); the row is
 * still written so the "months earned" stat and the audit trail are complete.</p>
 */
@Entity
@Table(name = "referral_rewards")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReferralReward {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "referrer_id", nullable = false, updatable = false)
    private UUID referrerId;

    @Column(name = "referred_user_id", nullable = false, updatable = false, unique = true)
    private UUID referredUserId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "granted_days", nullable = false)
    private int grantedDays;

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
