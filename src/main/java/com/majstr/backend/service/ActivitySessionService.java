package com.majstr.backend.service;

import com.majstr.backend.repository.UserActivitySessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Stretches of use for the admin page (V199). {@link com.majstr.backend.security.LastActiveTracker}
 * calls {@link #record} on every touch it lets through — once per 5 minutes per master — so a
 * session's end is known to within those 5 minutes and costs one UPDATE per touch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivitySessionService {

    /** Silence longer than this ends a session; the next request opens a new one. */
    public static final Duration GAP = Duration.ofMinutes(30);

    /** How long a session is kept — admin insight, not history. */
    static final Duration RETENTION = Duration.ofDays(90);

    private final UserActivitySessionRepository repository;

    /** Runs inside the tracker's own transaction. */
    public void record(UUID userId, Instant now) {
        if (repository.extendOpen(userId, now.minus(GAP), now) > 0) {
            return;
        }
        repository.open(UUID.randomUUID(), userId, now);
    }

    @Scheduled(cron = "${app.activity-sessions.retention-cron:0 40 3 * * *}")
    @Transactional
    public void purgeOld() {
        int removed = repository.deleteSeenBefore(Instant.now().minus(RETENTION));
        if (removed > 0) {
            log.info("Removed {} activity sessions older than {} days", removed, RETENTION.toDays());
        }
    }
}
