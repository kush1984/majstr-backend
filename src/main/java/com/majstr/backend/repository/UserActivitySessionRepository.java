package com.majstr.backend.repository;

import com.majstr.backend.entity.UserActivitySession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivitySessionRepository extends JpaRepository<UserActivitySession, UUID> {

    /** Stretch the master's open session — the one seen after {@code openSince} — to {@code now}. */
    @Modifying
    @Query(value = """
            UPDATE user_activity_session SET last_seen_at = :now
            WHERE id = (SELECT s.id FROM user_activity_session s
                        WHERE s.user_id = :userId AND s.last_seen_at > :openSince
                        ORDER BY s.last_seen_at DESC LIMIT 1)
            """, nativeQuery = true)
    int extendOpen(@Param("userId") UUID userId, @Param("openSince") Instant openSince, @Param("now") Instant now);

    /** A native insert, not {@code save()}: it runs NOW, inside the tracker's try, rather than at a
     *  commit flush that would throw into the auth filter (and the master's request) on a DB hiccup. */
    @Modifying
    @Query(value = """
            INSERT INTO user_activity_session (id, user_id, started_at, last_seen_at)
            VALUES (:id, :userId, :now, :now)
            """, nativeQuery = true)
    int open(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Query("SELECT s FROM UserActivitySession s WHERE s.userId = :userId ORDER BY s.startedAt DESC")
    List<UserActivitySession> findRecent(@Param("userId") UUID userId, Pageable pageable);

    /** Seconds of use since {@code since}; a session that began before it counts from {@code since}. */
    @Query(value = """
            SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (s.last_seen_at - GREATEST(s.started_at, :since)))), 0)
            FROM user_activity_session s
            WHERE s.user_id = :userId AND s.last_seen_at > :since
            """, nativeQuery = true)
    double secondsActiveSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM UserActivitySession s WHERE s.lastSeenAt < :before")
    int deleteSeenBefore(@Param("before") Instant before);
}
