package com.majstr.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStageTest {

    @Test
    void nothingYet_isAssessment() {
        assertThat(ObjectStage.derive(ProjectStatus.DRAFT, null, false, false))
                .isEqualTo(ObjectStage.ASSESSMENT);
    }

    @Test
    void aSentEstimateWithNoneSigned_isPendingSignature() {
        assertThat(ObjectStage.derive(ProjectStatus.ESTIMATING, null, false, true))
                .isEqualTo(ObjectStage.PENDING_SIGNATURE);
    }

    @Test
    void aSignedEstimate_isInProgress() {
        assertThat(ObjectStage.derive(ProjectStatus.IN_PROGRESS, null, true, false))
                .isEqualTo(ObjectStage.IN_PROGRESS);
    }

    @Test
    void signedTakesPriorityOverAlsoHavingASentVariant() {
        assertThat(ObjectStage.derive(ProjectStatus.IN_PROGRESS, null, true, true))
                .isEqualTo(ObjectStage.IN_PROGRESS);
    }

    @Test
    void completedAtSet_isCompleted_regardlessOfEstimates() {
        assertThat(ObjectStage.derive(ProjectStatus.COMPLETED, Instant.now(), true, true))
                .isEqualTo(ObjectStage.COMPLETED);
        assertThat(ObjectStage.derive(ProjectStatus.COMPLETED, Instant.now(), false, false))
                .isEqualTo(ObjectStage.COMPLETED);
    }

    @Test
    void cancelledStatus_isCancelled_evenWhenCompletedAndSigned() {
        // Top priority: a cancelled object reads CANCELLED no matter what else is true about it.
        assertThat(ObjectStage.derive(ProjectStatus.CANCELLED, Instant.now(), true, true))
                .isEqualTo(ObjectStage.CANCELLED);
    }

    @Test
    void cancelledWithNothingElseSet_isStillCancelled() {
        assertThat(ObjectStage.derive(ProjectStatus.CANCELLED, null, false, false))
                .isEqualTo(ObjectStage.CANCELLED);
    }
}
