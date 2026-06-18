package com.majstr.backend.repository;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Used by the first-admin auto-seed to stay idempotent. */
    boolean existsByRole(Role role);

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
    default Page<User> searchAdmin(Plan plan, String search, Pageable pageable) {
        return searchAdminByPattern(plan, likePattern(search), pageable);
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
              AND (
                :pattern IS NULL
                OR LOWER(u.email)       LIKE :pattern
                OR LOWER(u.fullName)    LIKE :pattern
                OR LOWER(u.companyName) LIKE :pattern
              )
            """)
    Page<User> searchAdminByPattern(@Param("plan") Plan plan,
                                    @Param("pattern") String pattern,
                                    Pageable pageable);

    @Modifying
    @Query("UPDATE User u SET u.lastActiveAt = :now WHERE u.id = :id")
    int touchLastActive(@Param("id") UUID id, @Param("now") Instant now);

    /** Spring Data projection used by {@link #countGroupByPlan()}. */
    interface PlanCount {
        Plan getPlan();
        long getTotal();
    }
}
