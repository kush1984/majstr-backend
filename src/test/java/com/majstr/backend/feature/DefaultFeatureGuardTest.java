package com.majstr.backend.feature;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFeatureGuardTest {

    private final DefaultFeatureGuard guard = new DefaultFeatureGuard();

    @Test
    void freeUserCannotUseClientPortal() {
        User user = userOnPlan(Plan.FREE);
        assertThatThrownBy(() -> guard.requireFeature(user, Feature.CLIENT_PORTAL))
                .isInstanceOf(FeatureNotAvailableException.class)
                .hasMessageContaining("CLIENT_PORTAL")
                .hasMessageContaining("FREE")
                .hasMessageContaining("PRO");
        assertThat(guard.isEnabled(user, Feature.CLIENT_PORTAL)).isFalse();
    }

    @Test
    void proUserCanUseClientPortalAndBrandedPdf() {
        User user = userOnPlan(Plan.PRO);
        assertThatCode(() -> guard.requireFeature(user, Feature.CLIENT_PORTAL))
                .doesNotThrowAnyException();
        assertThat(guard.isEnabled(user, Feature.BRANDED_PDF)).isTrue();
    }

    @Test
    void aiAssistantBelongsToTeamOnly() {
        assertThat(guard.isEnabled(userOnPlan(Plan.FREE), Feature.AI_ASSISTANT)).isFalse();
        assertThat(guard.isEnabled(userOnPlan(Plan.PRO),  Feature.AI_ASSISTANT)).isFalse();
        assertThat(guard.isEnabled(userOnPlan(Plan.TEAM), Feature.AI_ASSISTANT)).isTrue();
    }

    @Test
    void minimumPlanForBrandedPdfIsPro() {
        assertThat(PlanConfig.minimumPlanFor(Feature.BRANDED_PDF)).isEqualTo(Plan.PRO);
    }

    @Test
    void minimumPlanForAiAssistantIsTeam() {
        assertThat(PlanConfig.minimumPlanFor(Feature.AI_ASSISTANT)).isEqualTo(Plan.TEAM);
    }

    private User userOnPlan(Plan plan) {
        return User.builder().id(UUID.randomUUID()).plan(plan).build();
    }
}
