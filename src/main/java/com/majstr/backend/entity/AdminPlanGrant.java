package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A plan change made from the admin page (V199): who, when, for how long. History, never updated. */
@Entity
@Table(name = "admin_plan_grant")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AdminPlanGrant {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "actor_email", nullable = false, updatable = false)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20, updatable = false)
    private Plan plan;

    /** Null = no end. */
    @Column(name = "months", updatable = false)
    private Integer months;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
