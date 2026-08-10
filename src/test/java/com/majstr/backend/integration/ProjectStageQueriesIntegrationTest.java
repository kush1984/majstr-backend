package com.majstr.backend.integration;

import com.majstr.backend.entity.Estimate;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectRepository;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object-status-unification native queries ({@code nativeQuery = true}, so no Mockito test
 * reaches them), run against real Postgres — this is the exact bug class the prompt reported:
 * "За договором" (a different query, fixed separately) and the dashboard's "Очікує" both used to
 * disagree with the object list because they counted different things (estimates vs objects, or
 * the raw {@code ProjectStatus} column instead of the derived stage). These tests pin the fixed
 * queries directly.
 */
class ProjectStageQueriesIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EstimateRepository estimateRepository;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        User owner = userRepository.save(User.builder()
                .email(unique + "@majstr.test")
                .emailCanonical(unique + "@majstr.test")
                .passwordHash("x")
                .fullName("Майстер")
                .phone("+380000000000")
                .companyName("ФОП")
                .plan(Plan.PRO)
                .referralCode(unique.substring(0, 10))
                .build());
        ownerId = owner.getId();
    }

    @Test
    void countInProgressStage_countsObjectsWithASignedEstimate_onlyOncePerObject() {
        // Two SIGNED estimates on the SAME object must not double-count the object (COUNT DISTINCT).
        Project inProgress = project(ProjectStatus.ESTIMATING, null);
        estimate(inProgress, EstimateStatus.SIGNED);
        estimate(inProgress, EstimateStatus.SIGNED);
        Project pendingOnly = project(ProjectStatus.ESTIMATING, null);
        estimate(pendingOnly, EstimateStatus.SENT);
        Project nothingYet = project(ProjectStatus.DRAFT, null);

        assertThat(projectRepository.countInProgressStage(ownerId)).isEqualTo(1L);
    }

    @Test
    void countInProgressStage_excludesCancelledAndCompleted_evenWithASignedEstimate() {
        Project cancelled = project(ProjectStatus.CANCELLED, null);
        estimate(cancelled, EstimateStatus.SIGNED);
        Project completed = project(ProjectStatus.COMPLETED, java.time.Instant.now());
        estimate(completed, EstimateStatus.SIGNED);

        assertThat(projectRepository.countInProgressStage(ownerId)).isZero();
    }

    @Test
    void countPendingSignatureStage_excludesObjectsThatAlreadyHaveASignedEstimate() {
        // The exact prod bug: an object with a SENT estimate must stop counting as "pending"
        // the moment it also has a SIGNED one — SIGNED wins the priority, not a raw estimate count.
        Project signed = project(ProjectStatus.ESTIMATING, null);
        estimate(signed, EstimateStatus.SENT);
        estimate(signed, EstimateStatus.SIGNED);
        Project pending = project(ProjectStatus.ESTIMATING, null);
        estimate(pending, EstimateStatus.SENT);
        estimate(pending, EstimateStatus.SENT); // two SENT variants on one object — still ONE object

        assertThat(projectRepository.countPendingSignatureStage(ownerId)).isEqualTo(1L);
    }

    @Test
    void findStageFlags_reportsSignedAndSentIndependently_perProject() {
        Project signedOnly = project(ProjectStatus.ESTIMATING, null);
        estimate(signedOnly, EstimateStatus.SIGNED);
        Project sentOnly = project(ProjectStatus.ESTIMATING, null);
        estimate(sentOnly, EstimateStatus.SENT);
        Project draftOnly = project(ProjectStatus.DRAFT, null);
        estimate(draftOnly, EstimateStatus.DRAFT);
        Project noEstimates = project(ProjectStatus.DRAFT, null);

        Map<UUID, Object[]> byId = new java.util.HashMap<>();
        for (Object[] row : projectRepository.findStageFlags(
                List.of(signedOnly.getId(), sentOnly.getId(), draftOnly.getId(), noEstimates.getId()))) {
            byId.put((UUID) row[0], row);
        }

        assertThat(Boolean.TRUE.equals(byId.get(signedOnly.getId())[1])).isTrue();  // has_signed
        assertThat(Boolean.TRUE.equals(byId.get(signedOnly.getId())[2])).isFalse(); // has_sent
        assertThat(Boolean.TRUE.equals(byId.get(sentOnly.getId())[1])).isFalse();
        assertThat(Boolean.TRUE.equals(byId.get(sentOnly.getId())[2])).isTrue();
        assertThat(Boolean.TRUE.equals(byId.get(draftOnly.getId())[1])).isFalse();
        assertThat(Boolean.TRUE.equals(byId.get(draftOnly.getId())[2])).isFalse();
        // A project with literally no estimates gets no row at all — the caller treats absence
        // as false/false, so it must simply not appear here.
        assertThat(byId).doesNotContainKey(noEstimates.getId());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Project project(ProjectStatus status, java.time.Instant completedAt) {
        return projectRepository.save(Project.builder()
                .owner(userRepository.findById(ownerId).orElseThrow())
                .name("Обʼєкт").address("вул. Тестова, 1")
                .status(status).completedAt(completedAt)
                .build());
    }

    private void estimate(Project project, EstimateStatus status) {
        estimateRepository.save(Estimate.builder()
                .project(project)
                .status(status)
                .build());
    }
}
