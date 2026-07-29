package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "project_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectMessage {
    // Pinned to the OBJECT, not to an estimate: the master's message link can be sent to anyone, and
    // what comes back has no estimate at all. The estimate stays as optional context — which quote
    // was being discussed — and its FK is ON DELETE SET NULL, so removing one estimate forgets that
    // context without deleting the conversation about the job.

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Which quote was being discussed, when there was one. Null for a message sent through the link. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id")
    private Estimate estimate;

    @Column(name = "author_name", length = 255)
    private String authorName;

    @Column(name = "author_phone", length = 50)
    private String authorPhone;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "author_ip", length = 64)
    private String authorIp;

    /** Whether the contractor has seen this question. New questions start unread. */
    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Attachments, oldest first — the order they were picked in the form.
     *
     * <p>Mapped here rather than fetched separately so {@link com.majstr.backend.dto.MessageView} can
     * simply read them: a view assembled from a list passed in alongside would silently show no
     * attachments wherever a caller forgot to pass one. The list query fetch-joins this; a single
     * message loaded by id pays one extra query, which is correct either way.</p>
     *
     * <p>No cascade: the stored bytes have to be deleted before the row goes, so removal runs through
     * the service. The database CASCADEs the rows themselves.</p>
     */
    @OneToMany(mappedBy = "message", fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ProjectMessageFile> files = new ArrayList<>();

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
