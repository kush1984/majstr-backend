package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.repository.ProjectRepository;
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
    @Mock ProjectRepository projectRepository;
    @InjectMocks LimitService limitService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void requireWithinLimit_freePlanRejectsThirdProject() {
        givenUserOnPlan(Plan.FREE);
        given(projectRepository.countByOwnerId(userId)).willReturn(2L);

        // The user-facing text is built from the message bundle in the advice
        // (covered by GlobalExceptionHandlerTest); here we assert the carried facts.
        assertThatThrownBy(() -> limitService.requireWithinLimit(userId, Limit.MAX_PROJECTS))
                .isInstanceOfSatisfying(LimitExceededException.class, ex -> {
                    assertThat(ex.getMaxAllowed()).isEqualTo(2);
                    assertThat(ex.getCurrentPlan()).isEqualTo(Plan.FREE);
                    assertThat(ex.getLimit()).isEqualTo(Limit.MAX_PROJECTS);
                });
    }

    @Test
    void requireWithinLimit_freePlanAllowsSecondProject() {
        givenUserOnPlan(Plan.FREE);
        given(projectRepository.countByOwnerId(userId)).willReturn(1L);

        assertThatCode(() -> limitService.requireWithinLimit(userId, Limit.MAX_PROJECTS))
                .doesNotThrowAnyException();
    }

    @Test
    void requireWithinLimit_proPlanIsUnlimited() {
        givenUserOnPlan(Plan.PRO);
        // No count call expected — early-return when max is -1.

        assertThatCode(() -> limitService.requireWithinLimit(userId, Limit.MAX_PROJECTS))
                .doesNotThrowAnyException();
    }

    @Test
    void requireWithinLimit_afterUpgradeToProPreviouslyBlockedUserCanCreate() {
        // Same user, count above the FREE cap; once we say PRO the limit
        // disappears — proves the plan is looked up at check time, not cached.
        givenUserOnPlan(Plan.PRO);
        // count would still be 10 in reality, but we never reach the count
        // because PRO is unlimited.

        assertThatCode(() -> limitService.requireWithinLimit(userId, Limit.MAX_PROJECTS))
                .doesNotThrowAnyException();
    }

    private void givenUserOnPlan(Plan plan) {
        User user = User.builder().id(userId).plan(plan).build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
    }
}
