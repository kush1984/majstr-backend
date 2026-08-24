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

    /** Canonical form of {@link #email} for duplicate-account detection only (NOT the
     *  login address). Gmail aliases (dots, +tag) collapse to one value — see
     *  {@code EmailPolicyService}. Set on register / unverified email change. */
    @Column(name = "email_canonical", nullable = false, length = 255)
    private String emailCanonical;

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

    // ---- Document requisites (acts iteration, V103) — all optional; used to render an «Акт
    //      виконаних робіт» PDF. Fallbacks at render time: legalName → companyName → fullName;
    //      a blank docCity omits the city line. ---------------------------------------------------

    /** ПІБ ФОП / повна назва юрособи (fallback for the PDF: companyName → fullName). */
    @Column(name = "legal_name", length = 255)
    private String legalName;

    /** РНОКПП — the individual tax number. Distinct from {@link #vatId}. */
    @Column(name = "tax_id", length = 20)
    private String taxId;

    @Column(name = "legal_address", length = 512)
    private String legalAddress;

    @Column(name = "iban", length = 64)
    private String iban;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    /** Whether the master is a VAT payer. When true the PDF prints the VAT block
     *  ({@link #vatId} + «Разом без ПДВ / ПДВ 20% / Разом з ПДВ»); when false, «Не є платником ПДВ»
     *  + the simplified-tax line. */
    @Builder.Default
    @Column(name = "vat_payer", nullable = false)
    private boolean vatPayer = false;

    /** ІПН платника ПДВ — the VAT payer number, only meaningful when {@link #vatPayer}. */
    @Column(name = "vat_id", length = 20)
    private String vatId;

    /** Єдиний податок group (2 / 3); null = not on the simplified system, or unset. */
    @Column(name = "tax_group")
    private Short taxGroup;

    /** Єдиний податок rate, %. */
    @Column(name = "tax_rate", precision = 5, scale = 2)
    private java.math.BigDecimal taxRate;

    /** Місто складання документів; blank → the city line is simply omitted from the PDF. */
    @Column(name = "doc_city", length = 120)
    private String docCity;

    /** How act numbers are formatted for this master («7» vs «7/2026»). */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "act_number_format", nullable = false, length = 20)
    private ActNumberFormat actNumberFormat = ActNumberFormat.PLAIN;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20)
    @Builder.Default
    private Plan plan = Plan.FREE;

    /** Objects this master has EVER created (never decremented on delete) — the basis for the FREE
     *  object cap, so a delete can't be used to slip past it. Seeded from the current object count
     *  for accounts predating V107. */
    @Column(name = "lifetime_project_count", nullable = false)
    private int lifetimeProjectCount;

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

    /** When the master activated the one-time 5-day PRO trial. NULL = never used;
     *  set once and never cleared (even after the trial lapses to FREE), so the
     *  trial can be claimed at most once and admin can see who tried PRO. */
    @Column(name = "trial_started_at")
    private Instant trialStartedAt;

    /** First-touch referral source for partner rev-share (DIRECT by default; a
     *  partner code like LIGA when the master arrived via a ?ref= link or promo).
     *  Set ONCE at registration, never auto-overwritten — only an admin can edit
     *  it (conflicts / survey leads). Source of truth for future rev-share. */
    @Column(name = "referral_source", nullable = false, length = 40)
    @Builder.Default
    private String referralSource = "DIRECT";

    /** First-touch UTM tags (V114) — the CHANNEL the master arrived through, kept apart from
     *  {@link #referralSource}, which is the PARTNER. Both dimensions are real at once: a master
     *  can follow a partner link from TikTok. Stamped once at registration, never overwritten.
     *  <b>NULL is legitimate</b> ("arrived with no tags") — there is no DIRECT-style sentinel,
     *  because that would merge "no tags" with "a tag that said direct". */
    @Column(name = "utm_source", length = 60)
    private String utmSource;

    @Column(name = "utm_medium", length = 60)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

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
     *  ({@code MONTH} → 299/30d, {@code HALF_YEAR} → 1494/180d, {@code YEAR} → 2748/360d).
     *  NULL when auto-renew is off. Set on the tokenizing checkout so a 6-month or annual
     *  subscription renews for the same span, not one month. */
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

    /**
     * When the "your trial is ending" reminder last went out.
     *
     * <p>Compared against TODAY, not against the billing cycle — unlike {@link #renewReminderSentAt}
     * this reminder repeats, once a day over the trial's last three days. A trial running out is
     * the master losing features he is actively using, and that is worth saying more than once;
     * the field exists so a job restart on the same day cannot say it twice.</p>
     */
    @Column(name = "trial_reminder_sent_at")
    private Instant trialReminderSentAt;

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
