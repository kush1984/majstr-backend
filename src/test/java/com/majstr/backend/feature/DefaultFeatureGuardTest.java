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
    void freeUserCanUsePortalSignatureAndPhotos() {
        User user = userOnPlan(Plan.FREE);
        assertThatCode(() -> guard.requireFeature(user, Feature.CLIENT_PORTAL))
                .doesNotThrowAnyException();
        assertThat(guard.isEnabled(user, Feature.ONLINE_SIGNATURE)).isTrue();
        assertThat(guard.isEnabled(user, Feature.PHOTO_REPORTS)).isTrue();
    }

    @Test
    void freeUserCannotUseBrandedPdf() {
        User user = userOnPlan(Plan.FREE);
        assertThat(guard.isEnabled(user, Feature.BRANDED_PDF)).isFalse();
        assertThatThrownBy(() -> guard.requireFeature(user, Feature.BRANDED_PDF))
                .isInstanceOf(FeatureNotAvailableException.class)
                .hasMessageContaining("BRANDED_PDF")
                .hasMessageContaining("FREE")
                .hasMessageContaining("PRO");
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
    void minimumPlanForClientPortalIsFree() {
        assertThat(PlanConfig.minimumPlanFor(Feature.CLIENT_PORTAL)).isEqualTo(Plan.FREE);
    }

    @Test
    void minimumPlanForAiAssistantIsTeam() {
        assertThat(PlanConfig.minimumPlanFor(Feature.AI_ASSISTANT)).isEqualTo(Plan.TEAM);
    }

    @Test
    void objectEconomyIsTemporarilyOpenToFreeToo() {
        // TEMPORARY business decision — see the comment on Plan.FREE in PlanConfig: opened up to
        // FREE while the AI-calling flows are hidden in the PWA to cut AI spend.
        assertThat(guard.isEnabled(userOnPlan(Plan.FREE), Feature.OBJECT_ECONOMY)).isTrue();
        assertThat(guard.isEnabled(userOnPlan(Plan.PRO),  Feature.OBJECT_ECONOMY)).isTrue();
        assertThat(guard.isEnabled(userOnPlan(Plan.TEAM), Feature.OBJECT_ECONOMY)).isTrue();
        assertThat(PlanConfig.minimumPlanFor(Feature.OBJECT_ECONOMY)).isEqualTo(Plan.FREE);
    }

    @Test
    void measurementsAreTemporarilyOpenToFreeToo() {
        // Same TEMPORARY decision as objectEconomyIsTemporarilyOpenToFreeToo, same reason.
        assertThat(guard.isEnabled(userOnPlan(Plan.FREE), Feature.MEASUREMENTS)).isTrue();
        assertThat(guard.isEnabled(userOnPlan(Plan.PRO),  Feature.MEASUREMENTS)).isTrue();
        assertThat(guard.isEnabled(userOnPlan(Plan.TEAM), Feature.MEASUREMENTS)).isTrue();
        assertThat(PlanConfig.minimumPlanFor(Feature.MEASUREMENTS)).isEqualTo(Plan.FREE);
    }

    private User userOnPlan(Plan plan) {
        return User.builder().id(UUID.randomUUID()).plan(plan).build();
    }
}
