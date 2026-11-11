package com.majstr.backend.service;

import com.majstr.backend.dto.AdminUserActivity;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.AdminPlanGrantRepository;
import com.majstr.backend.repository.UserActivitySessionRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Reads the admin card's «Активність» block (V199). */
@Service
@RequiredArgsConstructor
public class AdminActivityService {

    static final int SESSIONS_SHOWN = 20;
    static final int GRANTS_SHOWN = 10;

    private final UserRepository userRepository;
    private final UserActivitySessionRepository sessionRepository;
    private final AdminPlanGrantRepository grantRepository;

    @Transactional(readOnly = true)
    public AdminUserActivity activity(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        Instant now = Instant.now();
        Instant openSince = now.minus(ActivitySessionService.GAP);
        return new AdminUserActivity(
                minutes(sessionRepository.secondsActiveSince(userId, now.minus(Duration.ofDays(7)))),
                minutes(sessionRepository.secondsActiveSince(userId, now.minus(Duration.ofDays(30)))),
                sessionRepository.findRecent(userId, PageRequest.of(0, SESSIONS_SHOWN)).stream()
                        .map(s -> new AdminUserActivity.Session(s.getStartedAt(), s.getLastSeenAt(),
                                Duration.between(s.getStartedAt(), s.getLastSeenAt()).toMinutes(),
                                s.getLastSeenAt().isAfter(openSince)))
                        .toList(),
                grantRepository.findRecent(userId, PageRequest.of(0, GRANTS_SHOWN)).stream()
                        .map(g -> new AdminUserActivity.Grant(g.getCreatedAt(), g.getActorEmail(), g.getPlan(),
                                g.getMonths(), g.getExpiresAt()))
                        .toList());
    }

    private static long minutes(double seconds) {
        return Math.round(seconds / 60);
    }
}
