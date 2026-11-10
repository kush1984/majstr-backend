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
 * One PDF the server handed out (V196) — the funnel's «Сформували PDF» and the client's download
 * count. Plain id columns, no associations: nothing ever loads the document through it, and nothing
 * about the person who downloaded it is kept.
 */
@Entity
@Table(name = "pdf_render")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class PdfRender {

    public enum Document { ESTIMATE, ACT }

    /** Who asked for it: the master in the app, or the client from a link. */
    public enum Reader { MASTER, CLIENT }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document", nullable = false, length = 10, updatable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(name = "reader", nullable = false, length = 10, updatable = false)
    private Reader reader;

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
