package com.majstr.backend.feature;

import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateRepository;
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
    private final EstimateRepository estimateRepository;

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

    /**
     * Enforces the per-project estimate cap (FREE). Counts ALL estimates of the
     * project (any status — abuse is via drafts), so deleting one frees a slot.
     * The caller must have already verified the project belongs to the user.
     */
    @Transactional(readOnly = true)
    public void requireCanAddEstimate(UUID userId, UUID projectId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        int max = PlanConfig.limit(user.getPlan(), Limit.MAX_ESTIMATES_PER_PROJECT);
        if (max < 0) {
            return; // unlimited
        }
        long current = estimateRepository.countByProjectId(projectId);
        if (current >= max) {
            throw new LimitExceededException(Limit.MAX_ESTIMATES_PER_PROJECT, max, user.getPlan());
        }
    }

    /** The current user's plan limits, for the UI to disable "create" buttons
     *  preemptively (null = unlimited). The backend check stays the source of truth. */
    @Transactional(readOnly = true)
    public PlanLimitsResponse limitsFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return PlanLimitsResponse.of(user.getPlan());
    }

    private long countFor(UUID userId, Limit limit) {
        return switch (limit) {
            case MAX_PROJECTS -> projectRepository.countByOwnerId(userId);
            case MAX_ESTIMATES_PER_PROJECT ->
                    throw new IllegalArgumentException("Per-project limit needs a project; use requireCanAddEstimate");
        };
    }
}
