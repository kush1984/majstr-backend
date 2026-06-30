package com.majstr.backend.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"passwordHash", "trades"})
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /**
     * Trades the contractor works in — at least one. Modeled as an
     * {@link ElementCollection} (a value set owned by the user) rather than
     * a separate entity: trades have no identity or attributes of their own
     * and nothing references them. LAZY + {@link BatchSize} keeps it cheap;
     * callers that serialize trades outside a transaction must load them in
     * a session (see {@code /auth/me} and the admin user list).
     */
    @ElementCollection(fetch = jakarta.persistence.FetchType.LAZY)
    @CollectionTable(name = "user_trades", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "trade", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 100)
    @Builder.Default
    private Set<Trade> trades = new LinkedHashSet<>();

    @Column(name = "phone", nullable = false, length = 50)
    private String phone;

    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.FREE;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** Soft email verification — new users start false; only "send to client" actions require it. */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Catalog-template version the user last pulled defaults from. "Add new from
     *  catalog" only offers templates added in a newer version (never re-adds what
     *  the master deleted/renamed). Set to the current version on register/reset. */
    @Column(name = "last_synced_catalog_version", nullable = false)
    @Builder.Default
    private int lastSyncedCatalogVersion = 0;

    /** When the master agreed to the Privacy Policy — set at registration (consent
     *  checkbox) or via the one-time login modal for users who registered before
     *  the checkbox existed. NULL = not yet consented. */
    @Column(name = "consented_to_privacy_at")
    private Instant consentedToPrivacyAt;

    /** When the master confirmed responsibility for entering client data (the
     *  controller/operator distinction). NULL = acknowledgement not accepted yet. */
    @Column(name = "acknowledged_client_data_at")
    private Instant acknowledgedClientDataAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (plan == null) {
            plan = Plan.FREE;
        }
        if (role == null) {
            role = Role.USER;
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
