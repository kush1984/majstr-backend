package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One master taking a SYSTEM DEFAULT template out of their own list — per master,
 * invisible to everyone else (same shape as {@link TemplateTradeOverride}).
 *
 * <p>A row exists for two reasons, told apart by {@code forkedTemplateId}: the master
 * <b>deleted</b> a default they never use ({@code null}), or the master <b>edited</b> one and the
 * service copied it into their own editable template ({@code forkedTemplateId} points at the
 * copy). Either way the default itself stops being listed.</p>
 *
 * <p>{@code forked_template_id} is {@code ON DELETE SET NULL}: deleting the copy later leaves the
 * default hidden, not silently restored.</p>
 */
@Entity
@Table(name = "template_default_override")
@IdClass(TemplateDefaultOverride.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"userId", "templateId"})
public class TemplateDefaultOverride {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    /** The master's own editable copy that replaced the default; null = plain hide. */
    @Column(name = "forked_template_id")
    private UUID forkedTemplateId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Composite key: one row per master per default template. */
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
