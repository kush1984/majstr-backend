package com.majstr.backend.feature;

import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LimitServiceTest {

    @Mock UserRepository userRepository;
    @Mock EstimateRepository estimateRepository;
    @Mock ProjectPhotoRepository projectPhotoRepository;
    @InjectMocks LimitService limitService;

    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @Test
    void reserveProjectSlot_freePlanRejectsThirdProject() {
        // The cap counts objects EVER created (lifetime), not the live row count — a FREE user who
        // already created 2 is refused a third even after deleting some.
        givenUserWithLifetime(Plan.FREE, 2);

        assertThatThrownBy(() -> limitService.reserveProjectSlot(userId))
                .isInstanceOfSatisfying(LimitExceededException.class, ex -> {
                    assertThat(ex.getMaxAllowed()).isEqualTo(2);
                    assertThat(ex.getCurrentPlan()).isEqualTo(Plan.FREE);
                    assertThat(ex.getLimit()).isEqualTo(Limit.MAX_PROJECTS);
                });
    }

    @Test
    void reserveProjectSlot_freePlanAllowsSecondProjectAndIncrementsLifetime() {
        User user = givenUserWithLifetime(Plan.FREE, 1);

        assertThatCode(() -> limitService.reserveProjectSlot(userId)).doesNotThrowAnyException();
        // The reservation is the increment — a delete never gives this slot back.
        assertThat(user.getLifetimeProjectCount()).isEqualTo(2);
    }

    @Test
    void reserveProjectSlot_proPlanIsUnlimited() {
        User user = givenUserWithLifetime(Plan.PRO, 50);

        assertThatCode(() -> limitService.reserveProjectSlot(userId)).doesNotThrowAnyException();
        assertThat(user.getLifetimeProjectCount()).isEqualTo(51); // still counted, just never capped
    }

    @Test
    void reserveProjectSlot_afterUpgradeToProPreviouslyBlockedUserCanCreate() {
        // Lifetime above the FREE cap; once PRO the limit disappears — plan looked up at check time.
        givenUserWithLifetime(Plan.PRO, 10);

        assertThatCode(() -> limitService.reserveProjectSlot(userId)).doesNotThrowAnyException();
    }

    // ---- per-project estimate limit (FREE) ---------------------------------

    @Test
    void requireCanAddEstimate_freeRejectsFourthEstimateOnProject() {
        givenUserOnPlan(Plan.FREE);
        given(estimateRepository.countByProjectId(projectId)).willReturn(3L);

        assertThatThrownBy(() -> limitService.requireCanAddEstimate(userId, projectId))
                .isInstanceOfSatisfying(LimitExceededException.class, ex -> {
                    assertThat(ex.getMaxAllowed()).isEqualTo(3);
                    assertThat(ex.getLimit()).isEqualTo(Limit.MAX_ESTIMATES_PER_PROJECT);
                    assertThat(ex.getCurrentPlan()).isEqualTo(Plan.FREE);
                });
    }

    @Test
    void requireCanAddEstimate_freeAllowsThirdEstimateOnProject() {
        givenUserOnPlan(Plan.FREE);
        given(estimateRepository.countByProjectId(projectId)).willReturn(2L);

        assertThatCode(() -> limitService.requireCanAddEstimate(userId, projectId))
                .doesNotThrowAnyException();
    }

    @Test
    void requireCanAddEstimate_proIsUnlimited_neverCounts() {
        givenUserOnPlan(Plan.PRO);
        // unlimited → early return before the count query

        assertThatCode(() -> limitService.requireCanAddEstimate(userId, projectId))
                .doesNotThrowAnyException();
    }

    // ---- per-object photo caps (separate progress / receipt budgets) -------

    @Test
    void requireCanAddPhoto_freeRejectsSixthProgressPhoto() {
        givenUserOnPlan(Plan.FREE);
        given(projectPhotoRepository.countByProjectIdAndSource(projectId, PhotoSource.MANUAL)).willReturn(5L);

        assertThatThrownBy(() -> limitService.requireCanAddPhoto(userId, projectId, PhotoSource.MANUAL))
                .isInstanceOfSatisfying(LimitExceededException.class, ex -> {
                    assertThat(ex.getMaxAllowed()).isEqualTo(5);
                    assertThat(ex.getLimit()).isEqualTo(Limit.MAX_PHOTOS_PER_OBJECT);
                    assertThat(ex.getCurrentPlan()).isEqualTo(Plan.FREE);
                });
    }

    @Test
    void requireCanAddPhoto_receiptUsesItsOwnBudget() {
        givenUserOnPlan(Plan.PRO);
        // 49 receipts < 50 cap → allowed, and the progress-photo count is never consulted.
        given(projectPhotoRepository.countByProjectIdAndSource(projectId, PhotoSource.RECEIPT)).willReturn(49L);

        assertThatCode(() -> limitService.requireCanAddPhoto(userId, projectId, PhotoSource.RECEIPT))
                .doesNotThrowAnyException();
    }

    // ---- limits-for-UI -----------------------------------------------------

    @Test
    void limitsFor_freeReturnsAllCaps() {
        givenUserOnPlanUnlocked(Plan.FREE);

        PlanLimitsResponse limits = limitService.limitsFor(userId);

        assertThat(limits.plan()).isEqualTo(Plan.FREE);
        assertThat(limits.maxProjects()).isEqualTo(2);
        assertThat(limits.maxEstimatesPerProject()).isEqualTo(3);
        assertThat(limits.maxPhotosPerObject()).isEqualTo(5);
        assertThat(limits.maxReceiptPhotosPerObject()).isEqualTo(0);
    }

    @Test
    void limitsFor_proReturnsNullsForUnlimitedButRealPhotoCaps() {
        givenUserOnPlanUnlocked(Plan.PRO);

        PlanLimitsResponse limits = limitService.limitsFor(userId);

        assertThat(limits.maxProjects()).isNull();
        assertThat(limits.maxEstimatesPerProject()).isNull();
        // Photo caps are real numbers even on PRO (not unlimited).
        assertThat(limits.maxPhotosPerObject()).isEqualTo(50);
        assertThat(limits.maxReceiptPhotosPerObject()).isEqualTo(50);
    }

    /**
     * The require* checks load the user FOR UPDATE — the row lock is what makes
     * "count, then insert" atomic, so stubbing the plain finder here would let the guard
     * silently stop locking and every test still pass.
     */
    private void givenUserOnPlan(Plan plan) {
        User user = User.builder().id(userId).plan(plan).build();
        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
    }

    /** A locked user with a preset lifetime object count — the basis of the project cap. */
    private User givenUserWithLifetime(Plan plan, int lifetime) {
        User user = User.builder().id(userId).plan(plan).lifetimeProjectCount(lifetime).build();
        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user));
        return user;
    }

    /** limitsFor is a pure read — no lock, hence the plain finder. */
    private void givenUserOnPlanUnlocked(Plan plan) {
        User user = User.builder().id(userId).plan(plan).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
    }
}
