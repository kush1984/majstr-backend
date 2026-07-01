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

import java.time.Instant;
import java.util.UUID;

/**
 * A PRO upgrade-intent event. CLICK = a tap on an upgrade CTA (recorded every time,
 * for intensity + by-trigger analytics); INTEREST = the painted-door form submitted
 * (a warm lead with an optional reason). Lightweight: {@code userId} is a plain
 * column (no association) — the admin leads view joins to {@code users} in JPQL.
 */
@Entity
@Table(name = "upgrade_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class UpgradeEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private UpgradeEventType type;

    /** Where the click came from (OBJECT_LIMIT / ESTIMATE_LIMIT / PROFILE / OTHER). */
    @Column(name = "trigger_source", length = 40)
    private String triggerSource;

    /** INTEREST only: what the master says they need. */
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

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
