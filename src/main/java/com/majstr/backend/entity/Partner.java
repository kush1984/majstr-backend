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
 * A referral partner (e.g. Ліга Майстрів) as DATA, so partners can be added
 * without a code change. {@code code} is what arrives via a {@code ?ref=<code>}
 * link or the registration promo field; it maps to {@code source} — the value
 * stamped once on {@link User#referralSource}. DIRECT is the implicit fallback
 * and is NOT stored here.
 */
@Entity
@Table(name = "partners")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Partner {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** What the user presents (ref param value or promo code), UNIQUE. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    /** The attribution value stamped on the user (e.g. LIGA). */
    @Column(name = "source", nullable = false, length = 40)
    private String source;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

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
