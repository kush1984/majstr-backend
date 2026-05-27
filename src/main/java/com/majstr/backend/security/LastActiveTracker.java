package com.majstr.backend.security;

import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Updates {@code users.last_active_at} from the auth filter without
 * hammering the database. A user can only re-trigger the UPDATE once
 * per {@link #THROTTLE}; otherwise the touch is silently skipped.
 *
 * <p>The in-memory map is per-instance and good enough for a single
 * node; for a multi-node deployment the throttle would need to live in
 * Redis. Worst case under load: each node re-issues one UPDATE per user
 * per throttle window, still tiny.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LastActiveTracker {

    private static final Duration THROTTLE = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final ConcurrentMap<UUID, Instant> lastTouched = new ConcurrentHashMap<>();

    /**
     * Records that a user just made an authenticated request. Idempotent
     * within {@link #THROTTLE}. Runs in its own transaction so a failure
     * here never poisons the request's main transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(UUID userId) {
        Instant now = Instant.now();
        Instant prev = lastTouched.get(userId);
        if (prev != null && Duration.between(prev, now).compareTo(THROTTLE) < 0) {
            return;
        }
        lastTouched.put(userId, now);
        try {
            userRepository.touchLastActive(userId, now);
        } catch (Exception e) {
            log.warn("Failed to update last_active_at for {}: {}", userId, e.getMessage());
        }
    }
}
