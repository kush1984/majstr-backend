package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One master's own filing of a template into a trade. Own templates carry the trade
 * on their own row; a SYSTEM default is shared by everyone, so re-filing it is stored
 * here — per master, invisible to others. {@code trade == null} on an existing row
 * means "explicitly general", which is different from having no row at all.
 */
@Entity
@Table(name = "template_trade_override")
@IdClass(TemplateTradeOverride.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"userId", "templateId"})
public class TemplateTradeOverride {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trade", length = 50)
    private Trade trade;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Composite key: one override per master per template. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private UUID templateId;
    }
}
