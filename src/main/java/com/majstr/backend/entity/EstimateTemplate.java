package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * A ready-made bundle of works for a typical job ("Санвузол повний") that a
 * master drops into a new estimate instead of assembling it line by line.
 *
 * <p>Two kinds, distinguished by {@code isDefault}: the 88 <b>system defaults</b>
 * ({@code isDefault=true}, {@code owner=null}, seeded in V28, visible to everyone)
 * and a master's <b>own</b> templates ({@code isDefault=false}, {@code owner} set,
 * created via "save estimate as template"). A DB CHECK enforces that pairing.</p>
 *
 * <p>Items are kept in {@code estimate_template_items} and loaded via their own
 * repository (mirrors how {@link Estimate} relates to {@link EstimateItem} — no
 * cascade collection, open-in-view is off). Template items carry no quantity and
 * no price: quantities are filled per object and prices come from the applying
 * master's own catalog at apply-time.</p>
 */
@Entity
@Table(name = "estimate_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EstimateTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Owner of a personal template; null for the system defaults (isDefault). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", updatable = false)
    private User owner;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Trade for the relevance filter; null = general (shown to everyone). */
    @Enumerated(EnumType.STRING)
    @Column(name = "trade", length = 50)
    private Trade trade;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
