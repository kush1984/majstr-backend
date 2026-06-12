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

    // ---- admin metrics ----------------------------------------------------

    long countByCreatedAtAfter(Instant since);

    long countByLastActiveAtAfter(Instant since);

    @Query("SELECT u.plan AS plan, COUNT(u) AS total FROM User u GROUP BY u.plan")
    List<PlanCount> countGroupByPlan();

    @Query("SELECT u FROM User u WHERE u.createdAt >= :since ORDER BY u.createdAt ASC")
    List<User> findRegisteredSince(@Param("since") Instant since);

    @Query("""
            SELECT u FROM User u
            WHERE (:plan IS NULL OR u.plan = :plan)
              AND (
                :search IS NULL
                OR LOWER(u.email)       LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.fullName)    LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.companyName) LIKE LOWER(CONCAT('%', :search, '%'))
              )
            """)
    Page<User> searchAdmin(@Param("plan") Plan plan,
                           @Param("search") String search,
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
