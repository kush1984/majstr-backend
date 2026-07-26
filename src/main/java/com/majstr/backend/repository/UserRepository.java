package com.majstr.backend.repository;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Loads the user row under a row-level write lock ({@code SELECT … FOR UPDATE}).
     *
     * <p>Used only by {@link com.majstr.backend.feature.LimitService} to close the
     * check-then-act race on plan quotas: counting rows and then inserting is not atomic, so
     * two concurrent creates could both see "2 of 3 used" and both succeed, leaving a FREE
     * account over its cap. Taking this lock first serialises a single user's creates — the
     * second waits for the first to COMMIT, then counts the row it just inserted and refuses
     * correctly.
     *
     * <p>The lock is held to the end of the caller's transaction, so it MUST be called from
     * inside the same transaction as the insert. Contention is per user (one master racing
     * themselves — realistically the offline outbox replaying a queue), never global.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Takes a transaction-scoped Postgres advisory lock keyed by a canonical email, so two
     * concurrent registrations for the same canonical address cannot both pass the duplicate
     * pre-check.
     *
     * <p>Why a lock and not a UNIQUE index: V55 made {@code idx_users_email_canonical}
     * non-unique <i>deliberately</i>, to grandfather legacy duplicates that predate the
     * column — adding the constraint now would fail on that existing data and block the
     * deploy. This closes the actual hole (the check-then-insert race) without disturbing
     * that decision.
     *
     * <p>It matters because the canonical column is anti-abuse: without it, firing several
     * parallel registrations for {@code j.o.hn+1@gmail.com}, {@code jo.hn+2@gmail.com} … all
     * pass the check together and farm one free plan per alias. Serialised, the first commits
     * and the rest see it and get a 409.
     *
     * <p>Released automatically at COMMIT or ROLLBACK — nothing to unlock by hand, and a
     * crashed request cannot leave a stuck lock. The subquery alias is required by Postgres;
     * the function itself returns void, hence the constant projection.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:canonical))) locked",
            nativeQuery = true)
    Integer lockCanonicalEmail(@Param("canonical") String canonical);

    /**
     * Loads a user with the lazy {@code trades} collection eager-fetched in the
     * same query, so the returned entity is safe to read or map to a DTO after
     * the session closes (open-in-view is off). Use this from any controller
     * that loads the user and then touches {@code trades} — a plain
     * {@code findById} leaves trades uninitialized and throws
     * {@code LazyInitializationException} on access.
     */
    @EntityGraph(attributePaths = "trades")
    Optional<User> findWithTradesById(UUID id);

    boolean existsByEmailIgnoreCase(String email);

    /** Anti-abuse duplicate detection: true if any account already resolves to this
     *  canonical email (gmail aliases collapsed — see {@code EmailPolicyService}). */
    boolean existsByEmailCanonical(String emailCanonical);

    /** Used by the first-admin auto-seed to stay idempotent. */
    boolean existsByRole(Role role);

    // ---- referrals --------------------------------------------------------

    /** Resolve a master's personal referral code (from an {@code m-<code>} link) to
     *  the owning user. Codes are stored lowercase; the resolver lowercases input. */
    Optional<User> findByReferralCode(String referralCode);

    /** Collision check for generating a new unique referral code at registration. */
    boolean existsByReferralCode(String referralCode);

    /** How many masters this one has invited (registered with their code). */
    long countByReferredByUserId(UUID referrerId);

    // ---- admin activation funnel ------------------------------------------

    /** Registered masters (excludes admins). */
    long countByRole(Role role);

    /** Masters who verified their email (funnel step). */
    long countByRoleAndEmailVerifiedTrue(Role role);

    // ---- admin metrics ----------------------------------------------------

    long countByCreatedAtAfter(Instant since);

    long countByLastActiveAtAfter(Instant since);

    @Query("SELECT u.plan AS plan, COUNT(u) AS total FROM User u GROUP BY u.plan")
    List<PlanCount> countGroupByPlan();

    @Query("SELECT u FROM User u WHERE u.createdAt >= :since ORDER BY u.createdAt ASC")
    List<User> findRegisteredSince(@Param("since") Instant since);

    /**
     * Admin user search: optional plan filter + optional case-insensitive
     * partial match on email / full name / company name. The LIKE pattern is
     * built in Java (see {@link #likePattern}) and compared against
     * {@code LOWER(column)} by {@link #searchAdminByPattern}.
     */
    default Page<User> searchAdmin(Plan plan, String source, String search, Pageable pageable) {
        String src = (source == null || source.isBlank()) ? null : source.trim().toUpperCase(Locale.ROOT);
        return searchAdminByPattern(plan, src, likePattern(search), pageable);
    }

    /**
     * The case-insensitive {@code %term%} LIKE pattern for {@link #searchAdmin},
     * or {@code null} for a blank/absent search (so the search clause is skipped
     * and all users — within the plan filter — are returned).
     *
     * <p>Lowercasing here, and comparing against {@code LOWER(column)}, keeps the
     * bind parameter a plain text LIKE operand. The previous form,
     * {@code LOWER(CONCAT('%', :search, '%'))}, buried the parameter inside
     * CONCAT/LOWER where Postgres couldn't infer its type — it defaulted to
     * {@code bytea}, and {@code lower(bytea)} doesn't exist, so admin search
     * 500'd with "function lower(bytea) does not exist".</p>
     */
    static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
    }

    @Query("""
            SELECT u FROM User u
            WHERE (:plan IS NULL OR u.plan = :plan)
              AND (:source IS NULL OR u.referralSource = :source)
              AND (
                :pattern IS NULL
                OR LOWER(u.email)       LIKE :pattern
                OR LOWER(u.fullName)    LIKE :pattern
                OR LOWER(u.companyName) LIKE :pattern
              )
            """)
    Page<User> searchAdminByPattern(@Param("plan") Plan plan,
                                    @Param("source") String source,
                                    @Param("pattern") String pattern,
                                    Pageable pageable);

    @Modifying
    @Query("UPDATE User u SET u.lastActiveAt = :now WHERE u.id = :id")
    int touchLastActive(@Param("id") UUID id, @Param("now") Instant now);

    /** Same throttled touch, but also records the device parsed from the
     *  User-Agent (only called when the UA yielded something meaningful). */
    @Modifying
    @Query("UPDATE User u SET u.lastActiveAt = :now, u.lastDeviceType = :deviceType, u.lastOs = :os WHERE u.id = :id")
    int touchLastActiveAndDevice(@Param("id") UUID id, @Param("now") Instant now,
                                 @Param("deviceType") String deviceType, @Param("os") String os);

    /** Billing-granted subscriptions whose expiry (+ grace, folded into the
     *  cutoff by the caller) has passed — the daily job downgrades these to FREE.
     *  Only rows with a non-null {@code planExpiresAt} qualify, so admin-granted
     *  plans (no expiry) are never touched. */
    @Query("SELECT u FROM User u WHERE u.planExpiresAt IS NOT NULL AND u.planExpiresAt < :cutoff")
    List<User> findExpiredSubscriptions(@Param("cutoff") Instant cutoff);

    /** Auto-renew subscriptions due a T-N warning email: opted in, tokenized, no
     *  reminder yet this cycle, expiring within the reminder window. */
    @Query("""
            SELECT u FROM User u
            WHERE u.autoRenew = true AND u.cardToken IS NOT NULL AND u.renewReminderSentAt IS NULL
              AND u.planExpiresAt IS NOT NULL
              AND u.planExpiresAt > :now AND u.planExpiresAt <= :reminderCutoff
            """)
    List<User> findAutoRenewReminderDue(@Param("now") Instant now, @Param("reminderCutoff") Instant reminderCutoff);

    /** Auto-renew subscriptions due a charge: opted in, tokenized, expired but still
     *  within grace (so a retry window remains before downgrade). */
    @Query("""
            SELECT u FROM User u
            WHERE u.autoRenew = true AND u.cardToken IS NOT NULL AND u.planExpiresAt IS NOT NULL
              AND u.planExpiresAt <= :now AND u.planExpiresAt > :graceStart
            """)
    List<User> findAutoRenewChargeDue(@Param("now") Instant now, @Param("graceStart") Instant graceStart);

    /** How many masters have auto-renew on (MRR signal for the admin). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.autoRenew = true")
    long countAutoRenewUsers();

    /** Registrations grouped by referral source (masters only) — the admin
     *  by-source report. One grouped query, no N+1. */
    @Query("""
            SELECT u.referralSource AS source, COUNT(u) AS cnt
            FROM User u
            WHERE u.role = com.majstr.backend.entity.Role.USER
            GROUP BY u.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countUsersBySource();

    /** Spring Data projection used by {@link #countGroupByPlan()}. */
    interface PlanCount {
        Plan getPlan();
        long getTotal();
    }
}
