package com.majstr.backend.integration;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.PlanLimitsResponse;
import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.EstimateService;
import com.majstr.backend.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One-off paid object (V214) against Postgres: a bought credit is spent by the create at the FREE cap,
 * stamps the object paid, and that object takes PRO's per-object caps.
 */
@SpringBootTest
class OneOffObjectIntegrationTest extends IntegrationTestBase {

    @Autowired ProjectService projectService;
    @Autowired EstimateService estimateService;
    @Autowired LimitService limitService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private User master;

    @BeforeEach
    void seed() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        master = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Разовий").emailVerified(true)
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10))
                .lifetimeProjectCount(2) // the FREE cap is already spent
                .build());
    }

    @Test
    void aBoughtObjectIsSpentAtTheCapAndThatObjectHasNoEstimateCap() {
        assertThatThrownBy(() -> create("Без оплати"))
                .isInstanceOf(LimitExceededException.class);

        // What the webhook does once monobank confirms the money (inside its transaction).
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> userRepository.addObjectCredit(master.getId()));
        assertThat(limitService.limitsFor(master.getId()).objectCredits()).isEqualTo(1);

        ProjectResponse paid = create("Оплачений обʼєкт");

        assertThat(paid.paid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM projects WHERE id = ?",
                Boolean.class, paid.id())).isTrue();
        PlanLimitsResponse after = limitService.limitsFor(master.getId());
        assertThat(after.objectCredits()).isZero();
        assertThat(after.projectsUsed()).isEqualTo(3);

        // FREE stops at 3 estimates per object; a paid object takes PRO's cap, which has none.
        for (int i = 0; i < 4; i++) {
            String name = "Кошторис " + i;
            assertThatCode(() -> estimateService.createForProject(
                    paid.id(), new EstimateCreateRequest(null, null, name), master.getId()))
                    .doesNotThrowAnyException();
        }

        // The credit is gone: the next object at the cap is refused again.
        assertThatThrownBy(() -> create("Ще один"))
                .isInstanceOf(LimitExceededException.class);
    }

    private ProjectResponse create(String name) {
        return projectService.create(new ProjectRequest(name, null, null, null), master.getId());
    }
}
