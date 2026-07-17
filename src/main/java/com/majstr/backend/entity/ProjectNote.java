package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * One note a master keeps against an object (project): free-text {@code body} plus an
 * optional {@code title} and {@code phone} (a subcontractor's contact, access conditions,
 * an agreement). Owner-scoped via the parent object; <b>no PRO gate</b> (a retention
 * utility). PRIVATE — never part of any estimate/portal/PDF/share response. Plain
 * {@code projectId} (no association) mirrors {@code ObjectExpense}/{@code ProjectPhoto}.
 *
 * <p>{@code phone} is stored verbatim (not normalised) — "067 123 45 67" and "+380…" both
 * work for a {@code tel:} link on the client.</p>
 */
@Entity
@Table(name = "project_note")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectNote {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

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
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
