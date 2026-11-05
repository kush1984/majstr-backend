package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/**
 * A stage of the payment plan that no longer exists (V93) — READ-ONLY. Nothing writes this table
 * any more (2026-11-05); its rows stay because a {@link PaymentReceipt} recorded against a stage has
 * no label of its own, and its name is still the stage's {@code purpose}. Only the columns that
 * answer that are mapped.
 */
@Entity
@Immutable
@Table(name = "project_payment")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectPayment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(name = "purpose", nullable = false, length = 255)
    private String purpose;
}
