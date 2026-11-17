package com.majstr.backend.feature;

import com.majstr.backend.config.BillingProperties;
import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final EstimateRepository estimateRepository;
    private final ProjectPhotoRepository projectPhotoRepository;
    private final ProjectRepository projectRepository;
    private final PaymentRepository paymentRepository;
    private final BillingProperties billingProperties;

    /**
     * Reserve one object slot for a create. The FREE cap counts objects EVER created
     * ({@code lifetime_project_count}), NOT the live row count — so deleting a completed/cancelled
     * object can't be used to slip past the limit (anti-abuse). On success the lifetime counter is
     * incremented in the SAME transaction (dirty-checked); a delete never decrements it. The user
     * row is locked ({@code findByIdForUpdate}) so the offline outbox replaying a burst of creates
     * can't race two past the cap (see the class doc).
     *
     * <p>At the cap, a bought one-off object (V214, {@code object_credits}) is spent instead of
     * refusing. Below the cap, or on a plan with none, a credit is never touched.</p>
     *
     * @return true when this create spent a one-off object credit — the caller stamps the object paid
     */
    @Transactional
    public boolean reserveProjectSlot(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        int max = PlanConfig.limit(user.getPlan(), Limit.MAX_PROJECTS);
        boolean paid = false;
        if (max >= 0 && user.getLifetimeProjectCount() >= max) {
            if (user.getObjectCredits() <= 0) {
                throw new LimitExceededException(Limit.MAX_PROJECTS, max, user.getPlan());
            }
            user.setObjectCredits(user.getObjectCredits() - 1);
            paid = true;
        }
        user.setLifetimeProjectCount(user.getLifetimeProjectCount() + 1);
        return paid;
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
        int max = limitOn(user, projectId, Limit.MAX_ESTIMATES_PER_PROJECT);
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
        int max = limitOn(user, projectId, limit);
        if (max < 0) {
            return; // unlimited
        }
        long current = projectPhotoRepository.countByProjectIdAndSource(projectId, source);
        if (current >= max) {
            throw new LimitExceededException(limit, max, user.getPlan());
        }
    }

    /**
     * A per-object cap on this object: the plan's, or PRO's on a one-off paid object (V214) when that
     * is looser. PRO's limits only — a paid object never gets a PRO feature.
     */
    private int limitOn(User user, UUID projectId, Limit limit) {
        int planMax = PlanConfig.limit(user.getPlan(), limit);
        if (planMax < 0 || !projectRepository.isPaid(projectId)) {
            return planMax;
        }
        int paidMax = PlanConfig.limit(Plan.PRO, limit);
        return paidMax < 0 ? paidMax : Math.max(planMax, paidMax);
    }

    /** The current user's plan limits + how many objects they've already used (lifetime), for the UI
     *  to disable "create" buttons preemptively and word the over-limit hint honestly (null cap =
     *  unlimited). The backend check stays the source of truth. */
    @Transactional(readOnly = true)
    public PlanLimitsResponse limitsFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        long objectsBoughtLastMonth = paymentRepository.countPaidObjectsSince(
                userId, Instant.now().minus(30, ChronoUnit.DAYS));
        return PlanLimitsResponse.of(user, billingProperties.trialDays(), billingProperties.objectPrice(),
                objectsBoughtLastMonth);
    }
}
