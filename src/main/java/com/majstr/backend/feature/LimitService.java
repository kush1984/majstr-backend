package com.majstr.backend.feature;

import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enforces per-plan numeric quotas. Throws {@link LimitExceededException}
 * (mapped to 403) when the user would cross the line defined by
 * {@link PlanConfig}. Counts are read live from the relevant repositories
 * so the limit always reflects on-disk reality, never a cached counter.
 */
@Service
@RequiredArgsConstructor
public class LimitService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public void requireWithinLimit(UUID userId, Limit limit) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        int max = PlanConfig.limit(user.getPlan(), limit);
        if (max < 0) {
            return; // unlimited
        }
        long current = countFor(userId, limit);
        if (current >= max) {
            throw new LimitExceededException(limit, max, user.getPlan());
        }
    }

    private long countFor(UUID userId, Limit limit) {
        return switch (limit) {
            case MAX_PROJECTS -> projectRepository.countByOwnerId(userId);
        };
    }
}
