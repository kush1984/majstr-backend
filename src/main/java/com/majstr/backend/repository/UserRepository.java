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
import java.util.Collection;
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
     *
     * <p>{@code activeSince} pins who counts as "active right now" for the ORDER BY — passed in
     * (not computed here) so the repository stays a plain data-access layer; the cutoff itself is
     * the caller's business decision. Registration order is newest-first (DESC) — see the
     * {@code registrationAscending} overload below for the toggle.</p>
     */
    default Page<User> searchAdmin(Plan plan, String source, String search, Instant activeSince, Pageable pageable) {
        String src = (source == null || source.isBlank()) ? null : source.trim().toUpperCase(Locale.ROOT);
        return searchAdminByPattern(plan, src, likePattern(search), idOrNull(search), activeSince, pageable);
    }

    /**
     * Same two-level order — active-right-now first, then registration date — but with the second
     * level's direction picked by the caller (the admin panel's clickable «Реєстрація» header). The
     * active bucket is always ON: an explicit registration sort narrows to WHICH end of the
     * inactive/tied rows comes first, it never demotes someone who is active right now.
     */
    default Page<User> searchAdmin(Plan plan, String source, String search, Instant activeSince,
                                    boolean registrationAscending, Pageable pageable) {
        if (!registrationAscending) {
            return searchAdmin(plan, source, search, activeSince, pageable);
        }
        String src = (source == null || source.isBlank()) ? null : source.trim().toUpperCase(Locale.ROOT);
        return searchAdminByPatternRegistrationAscending(
                plan, src, likePattern(search), idOrNull(search), activeSince, pageable);
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

    /**
     * The search term read as a user id, or {@code null} when it is not one.
     *
     * <p>This is the join between a PostHog session replay and a real person to phone. PostHog is
     * deliberately told nothing but the UUID — no name, no email, no phone (see
     * docs/iteration-posthog.md) — so the recording identifies its master by {@code distinct_id}
     * alone, and that id is worth nothing unless the admin panel can be handed it directly.
     * Pasting one used to match no row at all: the LIKE clause only ever looked at email, name and
     * company.</p>
     *
     * <p>An id is matched by EQUALITY, never by LIKE — a UUID is not a substring people type, and
     * turning it into {@code %…%} would be a sequential scan over a text cast of the primary key.
     * A term that does not parse is simply not an id, and the text search answers alone.</p>
     */
    static UUID idOrNull(String search) {
        if (search == null) {
            return null;
        }
        String term = search.trim();
        // The length check is NOT redundant. UUID.fromString only validates strictly at exactly 36
        // characters; anything shorter falls into a lenient path that just splits on "-" and parses
        // each group as hex, so a HALF-COPIED id ("866feca8-dc5b-403f-993e-c6") parses happily into a
        // DIFFERENT uuid. Without this, a bad paste would silently search for somebody else.
        if (term.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(term);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * Active-right-now users first (most recently active among them first), everyone else by
     * {@code createdAt DESC}. No sort on the incoming {@code Pageable} — this ORDER BY is the only
     * one.
     *
     * <p>The second sort key is deliberately {@code CASE WHEN <active> THEN u.lastActiveAt END}, not
     * a bare {@code u.lastActiveAt DESC}: {@code lastActiveAt} is stamped on every authenticated
     * request (throttled to 5 min, {@code LastActiveTracker}), so it is non-null for nearly every
     * user who has ever logged in — a bare {@code DESC} on it would rank the "everyone else" bucket
     * by stale last-login recency instead of registration date, and {@code createdAt} would only
     * ever break exact ties (it never got a chance to actually order anything, which is the bug this
     * comment exists to stop someone from reintroducing). Scoping the key to the active bucket makes
     * it NULL — sorted last under DESC by default — for everyone else, so createdAt fully controls
     * that bucket's order.</p>
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:plan IS NULL OR u.plan = :plan)
              AND (:source IS NULL OR u.referralSource = :source)
              AND (
                :pattern IS NULL
                OR LOWER(u.email)       LIKE :pattern
                OR LOWER(u.fullName)    LIKE :pattern
                OR LOWER(u.companyName) LIKE :pattern
                OR u.id = :id
              )
            ORDER BY
                CASE WHEN u.lastActiveAt > :activeSince THEN 0 ELSE 1 END,
                CASE WHEN u.lastActiveAt > :activeSince THEN u.lastActiveAt END DESC,
                u.createdAt DESC
            """)
    Page<User> searchAdminByPattern(@Param("plan") Plan plan,
                                    @Param("source") String source,
                                    @Param("pattern") String pattern,
                                    @Param("id") UUID id,
                                    @Param("activeSince") Instant activeSince,
                                    Pageable pageable);

    /** Same as {@link #searchAdminByPattern}, registration oldest-first instead of newest-first. */
    @Query("""
            SELECT u FROM User u
            WHERE (:plan IS NULL OR u.plan = :plan)
              AND (:source IS NULL OR u.referralSource = :source)
              AND (
                :pattern IS NULL
                OR LOWER(u.email)       LIKE :pattern
                OR LOWER(u.fullName)    LIKE :pattern
                OR LOWER(u.companyName) LIKE :pattern
                OR u.id = :id
              )
            ORDER BY
                CASE WHEN u.lastActiveAt > :activeSince THEN 0 ELSE 1 END,
                CASE WHEN u.lastActiveAt > :activeSince THEN u.lastActiveAt END DESC,
                u.createdAt ASC
            """)
    Page<User> searchAdminByPatternRegistrationAscending(@Param("plan") Plan plan,
                                    @Param("source") String source,
                                    @Param("pattern") String pattern,
                                    @Param("id") UUID id,
                                    @Param("activeSince") Instant activeSince,
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

    /**
     * Billing-granted subscriptions the daily job should downgrade to FREE. Only rows with a
     * non-null {@code planExpiresAt} qualify, so admin-granted plans (no expiry) are never touched.
     *
     * <p>The grace window applies ONLY where a charge could still land — auto-renew on, with a card
     * token. That is what grace is for: a late charge should not interrupt PRO. Everything else,
     * a trial above all, has nothing pending and expires the moment {@code planExpiresAt} passes.
     *
     * <p>This used to fold grace into a single cutoff for everyone, which quietly turned a 5-day
     * trial into nearly nine days: five of trial, three of grace meant for a charge that could
     * never arrive, plus the wait for the next nightly run.
     *
     * <p>The renewable branch matches {@code idx_users_auto_renew} exactly.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.planExpiresAt IS NOT NULL
              AND (
                    (u.autoRenew = true AND u.cardToken IS NOT NULL AND u.planExpiresAt < :graceCutoff)
                 OR ((u.autoRenew = false OR u.cardToken IS NULL) AND u.planExpiresAt < :now)
              )
            """)
    List<User> findExpiredSubscriptions(@Param("now") Instant now,
                                        @Param("graceCutoff") Instant graceCutoff);

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

    /**
     * Masters whose TRIAL ends within the window and who have not been told about it today.
     *
     * <p>The exclusions are the whole query. A master who has <b>paid</b> is on a subscription, not
     * a trial — telling him his trial is ending would be both wrong and alarming, and he is only
     * caught here at all because a trial he took months ago left {@code trialStartedAt} set
     * forever. A master already back on FREE has nothing ending. And {@code trialReminderSentAt}
     * is compared against the START OF TODAY rather than being cleared: this reminder repeats
     * daily, so "already sent" can only ever mean "already sent today".</p>
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.trialStartedAt IS NOT NULL
              AND u.plan <> com.majstr.backend.entity.Plan.FREE
              AND u.planExpiresAt IS NOT NULL
              AND u.planExpiresAt > :now AND u.planExpiresAt <= :cutoff
              AND (u.trialReminderSentAt IS NULL OR u.trialReminderSentAt < :startOfToday)
              AND NOT EXISTS (
                  SELECT 1 FROM Payment p
                  WHERE p.userId = u.id AND p.status = com.majstr.backend.entity.PaymentStatus.SUCCESS)
            """)
    List<User> findTrialEndingReminderDue(@Param("now") Instant now,
                                          @Param("cutoff") Instant cutoff,
                                          @Param("startOfToday") Instant startOfToday);

    /**
     * Everyone currently on a paid plan, for the admin's bought / trial / granted split.
     *
     * <p>Only the paid population, never the whole table: the split has to classify each of these
     * against the payments table, and pulling 130 FREE rows to discard them all is the kind of scan
     * that is fine at this size and is not fine later.</p>
     */
    @Query("SELECT u FROM User u WHERE u.plan <> com.majstr.backend.entity.Plan.FREE")
    List<User> findOnPaidPlan();

    /** Names for the admin's "who paid" list — one query for the whole page of payments. */
    List<User> findByIdIn(Collection<UUID> ids);

    /** Registrations grouped by referral source (masters only) — the admin
     *  by-source report. One grouped query, no N+1. */
    @Query("""
            SELECT u.referralSource AS source, COUNT(u) AS cnt
            FROM User u
            WHERE u.role = com.majstr.backend.entity.Role.USER
            GROUP BY u.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countUsersBySource();

    /** Email-verified masters grouped by referral source — funnel step 2, by source. */
    @Query("""
            SELECT u.referralSource AS source, COUNT(u) AS cnt
            FROM User u
            WHERE u.role = com.majstr.backend.entity.Role.USER AND u.emailVerified = true
            GROUP BY u.referralSource
            """)
    List<com.majstr.backend.dto.SourceCount> countVerifiedUsersBySource();

    /**
     * Registrations grouped by first-touch UTM source (V114) — the channel dimension, kept apart
     * from the partner dimension above.
     *
     * <p>{@code utm_source} is NULLABLE, and NULL means "arrived with no tags" — a legitimate row,
     * not a gap. The caller buckets it explicitly as «без UTM» instead of dropping it.</p>
     */
    @Query("""
            SELECT u.utmSource AS source, COUNT(u) AS cnt
            FROM User u
            WHERE u.role = com.majstr.backend.entity.Role.USER
            GROUP BY u.utmSource
            """)
    List<com.majstr.backend.dto.SourceCount> countUsersByUtmSource();

    /** Spring Data projection used by {@link #countGroupByPlan()}. */
    interface PlanCount {
        Plan getPlan();
        long getTotal();
    }
}
