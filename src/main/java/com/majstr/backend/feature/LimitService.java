package com.majstr.backend.feature;

import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
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
 *
 * <p><b>Why the require* methods lock and are not {@code readOnly}.</b> "Count, then insert"
 * is not atomic: two concurrent creates could each read "2 of 3 used", each decide there is
 * room, and both insert — leaving a FREE account permanently over its cap with no way for the
 * app to notice. Each check therefore opens by taking a write lock on the user row
 * ({@code findByIdForUpdate}), which serialises one user's concurrent creates: the second
 * blocks until the first commits, then counts the freshly inserted row and refuses correctly.
 *
 * <p>This is not theoretical here — the offline outbox replays a queue of creates back to
 * back on reconnect, which is exactly the burst that loses this race. The lock only ever
 * contends a user with themselves.
 *
 * <p>Consequence: every caller MUST already be inside the transaction that performs the
 * insert (all of them are {@code @Transactional}). A lock taken in its own short transaction
 * would be released before the insert and would guard nothing.
 */
@Service
@RequiredArgsConstructor
public class LimitService {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectPhotoRepository projectPhotoRepository;

    @Transactional
    public void requireWithinLimit(UUID userId, Limit limit) {
        User user = userRepository.findByIdForUpdate(userId)
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
    @Transactional
    public void requireCanAddEstimate(UUID userId, UUID projectId) {
        User user = userRepository.findByIdForUpdate(userId)
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

    /**
     * Enforces the per-object photo cap. Progress (MANUAL) and receipt (RECEIPT) photos
     * have separate budgets so receipts don't eat the progress cap. Counts live photos of
     * that source on the object. The caller must have verified the object belongs to the user.
     */
    @Transactional
    public void requireCanAddPhoto(UUID userId, UUID projectId, PhotoSource source) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        Limit limit = source == PhotoSource.RECEIPT
                ? Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT
                : Limit.MAX_PHOTOS_PER_OBJECT;
        int max = PlanConfig.limit(user.getPlan(), limit);
        if (max < 0) {
            return; // unlimited
        }
        long current = projectPhotoRepository.countByProjectIdAndSource(projectId, source);
        if (current >= max) {
            throw new LimitExceededException(limit, max, user.getPlan());
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
            case MAX_PHOTOS_PER_OBJECT, MAX_RECEIPT_PHOTOS_PER_OBJECT ->
                    throw new IllegalArgumentException("Per-object photo limit needs a project; use requireCanAddPhoto");
        };
    }
}
