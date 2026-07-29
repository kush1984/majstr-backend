package com.majstr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A file attached to a message — a photo or a PDF, from whoever the master gave the link to.
 *
 * <p>{@code contentType} is what the bytes actually are, sniffed on upload, never what the uploader
 * claimed. {@code storageKey} is opaque and never reaches the client, which addresses a file by id.</p>
 *
 * <p>{@code lastOpenedAt} is the retention clock: null means never opened, and the sweep falls back to
 * {@code createdAt} so a file nobody ever looked at still ages. {@code deletionWarnedAt} is the notice
 * that was given before deleting it.</p>
 */
@Entity
@Table(name = "project_message_files")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectMessageFile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false, updatable = false)
    private ProjectMessage message;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "original_name", length = 255, updatable = false)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_opened_at")
    private Instant lastOpenedAt;

    /**
     * When the master was told this file is about to be deleted. Null = not warned.
     *
     * <p>Cleared when the file is opened, which is the offer being made: look at it and it stays. That
     * also means the column doubles as the "delete after" clock — the sweep deletes what has carried a
     * warning for the grace period and has not been opened since.</p>
     */
    @Column(name = "deletion_warned_at")
    private Instant deletionWarnedAt;

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
