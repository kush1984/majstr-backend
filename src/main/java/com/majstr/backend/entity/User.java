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
@ToString(exclude = {"passwordHash", "trades", "cardToken"})
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

    /** Device the master last used, parsed from the User-Agent (throttled with
     *  last_active_at). MOBILE/TABLET/DESKTOP/UNKNOWN; null until first seen. */
    @Column(name = "last_device_type", length = 20)
    private String lastDeviceType;

    /** OS the master last used (iOS/Android/Windows/macOS/Linux/ChromeOS); null until seen. */
    @Column(name = "last_os", length = 40)
    private String lastOs;

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

    /** When a billing-granted PRO subscription ends. NULL = FREE, or an
     *  admin-granted plan with no expiry (admin owns that — never auto-downgraded).
     *  A successful payment extends it; the daily expiry job downgrades to FREE
     *  once it (+ grace) passes. */
    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;

    /** First-touch referral source for partner rev-share (DIRECT by default; a
     *  partner code like LIGA when the master arrived via a ?ref= link or promo).
     *  Set ONCE at registration, never auto-overwritten — only an admin can edit
     *  it (conflicts / survey leads). Source of truth for future rev-share. */
    @Column(name = "referral_source", nullable = false, length = 40)
    @Builder.Default
    private String referralSource = "DIRECT";

    /** This master's own personal referral code — the shareable link is
     *  {@code majstr.pro/?ref=m-<referralCode>}. Generated uniquely at registration
     *  (backfilled for existing users in V41). UNIQUE. */
    @Column(name = "referral_code", nullable = false, unique = true, length = 16)
    private String referralCode;

    /** The master who invited this one (first-touch via their {@code m-<code>} link
     *  or promo code). Set once at registration, never auto-changed. NULL = not
     *  referred by another master. Drives the referral reward on this user's first
     *  payment. */
    @Column(name = "referred_by_user_id")
    private UUID referredByUserId;

    /** Period auto-renew recharges with — the one the master bought while opting in
     *  ({@code MONTH} → 299/30d, {@code HALF_YEAR} → 1494/180d). NULL when auto-renew
     *  is off. Set on the tokenizing checkout so a 6-month subscription renews for
     *  6 months, not one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "renew_period", length = 20)
    private BillingPeriod renewPeriod;

    /** Auto-renewal of a billing-granted PRO. When true and a card token is stored,
     *  the scheduled job charges the saved card before {@code planExpiresAt}. The
     *  master can disable it in one tap (token kept for quick re-enable). */
    @Column(name = "auto_renew", nullable = false)
    @Builder.Default
    private boolean autoRenew = false;

    /** monobank card token for merchant-initiated auto-renew charges. SENSITIVE —
     *  never logged (excluded from toString), never returned by any API. */
    @Column(name = "card_token", length = 255)
    private String cardToken;

    /** Masked PAN for display only (e.g. {@code 424242****4242}). Safe to show. */
    @Column(name = "card_mask", length = 40)
    private String cardMask;

    /** The monobank wallet id we generated to tokenize the card at checkout. */
    @Column(name = "wallet_id", length = 64)
    private String walletId;

    /** When the T-3 auto-renew reminder was sent for the current period — cleared
     *  on every extension so exactly one reminder goes out per cycle. */
    @Column(name = "renew_reminder_sent_at")
    private Instant renewReminderSentAt;

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
