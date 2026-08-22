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
import java.util.List;
import java.util.UUID;

/**
 * A photo attached to an object (project): either a RECEIPT (its lines were parsed into an
 * estimate — always PRIVATE) or a MANUAL progress photo (PRIVATE by default, can be made
 * SHARED for the client portal). Belongs to a {@link Project} by id (FK ON DELETE CASCADE in
 * the migration). {@code estimateId} links a receipt to its estimate (FK ON DELETE SET NULL);
 * {@code estimateNameSnapshot} keeps a durable label if that estimate is later deleted. Owner
 * isolation is enforced via the project in the service, not here. The {@code storageKey} is an
 * opaque {@link com.majstr.backend.storage.StorageService} key — never exposed to the client.
 */
@Entity
@Table(name = "project_photo")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProjectPhoto {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private PhotoSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private PhotoVisibility visibility;

    @Column(name = "caption")
    private String caption;

    @Column(name = "estimate_id")
    private UUID estimateId;

    @Column(name = "estimate_name_snapshot")
    private String estimateNameSnapshot;

    /** The Фото tab's folder (photo-folders): the reserved {@link #FOLDER_RECEIPTS} = «Чеки», null
     *  = «Інше», anything else = a name the master invented. Just a label — a folder exists while
     *  a photo carries it. Never shown to the client; the portal ignores it. */
    @Column(name = "folder", length = 100)
    private String folder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Reserved folder value for receipt photos from ANY flow — rendered as «Чеки». */
    public static final String FOLDER_RECEIPTS = "RECEIPTS";

    /** The display labels the PWA renders for the two built-in folders, in every shipped locale.
     *  A master typing one of them by hand must fold onto the system value instead of creating a
     *  confusing twin folder, so the server matches them too. */
    public static final List<String> RECEIPTS_FOLDER_ALIASES = List.of("Чеки", "Receipts");
    public static final List<String> DEFAULT_FOLDER_ALIASES = List.of("Інше", "Other");

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
