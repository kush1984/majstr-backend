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
 * What one signature certifies (V150, review B-76): the canonical JSON snapshot of every value the
 * document printed, its SHA-256 and the stamped PDF produced at that moment. A signed act or
 * estimate is rendered FROM this row ever after, so a later edit to the master's requisites, the
 * client or the object never reaches a document somebody already signed.
 *
 * <p>{@code snapshot} is kept VERBATIM — it is the exact text {@code docHash} was computed over,
 * so it must never be re-serialised on the way back in.</p>
 */
@Entity
@Table(name = "signed_document")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class SignedDocument {

    public enum Kind { ACT, ESTIMATE }

    @Id
    private UUID id;

    @Column(name = "kind", nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind kind;

    @Column(name = "work_act_id")
    private UUID workActId;

    @Column(name = "estimate_id")
    private UUID estimateId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "snapshot", nullable = false, columnDefinition = "text")
    private String snapshot;

    @Column(name = "doc_hash", nullable = false, length = 64)
    private String docHash;

    /** The stamped PDF as it was produced at the signature; null only if storing it failed. */
    @Column(name = "pdf_key")
    private String pdfKey;

    /** This signature's own copy of the contractor's logo, so replacing the logo changes nothing. */
    @Column(name = "logo_key")
    private String logoKey;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
