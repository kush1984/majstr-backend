package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.AdminPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which ending plan gets which warning (V200): a trial keeps the trial reminder; everything else
 * dated and not about to renew gets «PRO закінчується»; nobody gets both, and nobody is missed.
 */
@SpringBootTest
class PlanEndingReminderQueryIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired AdminPlanService adminPlans;
    @Autowired JdbcTemplate jdbc;

    private User user(Plan plan, Instant expires, Instant trialStarted, boolean autoRenew, String card) {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        return userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Термін")
                .plan(plan).role(Role.USER).referralCode(unique.substring(0, 10))
                .planExpiresAt(expires).trialStartedAt(trialStarted).autoRenew(autoRenew).cardToken(card)
                .build());
    }

    private void paid(User u) {
        jdbc.update("INSERT INTO payments (id, user_id, provider, amount, ccy, status, plan, days, created_at) "
                + "VALUES (?, ?, 'MONOBANK', 299, 980, 'SUCCESS', 'PRO', 30, now())", UUID.randomUUID(), u.getId());
    }

    @Test
    void eachEndingPlanGetsExactlyOneKindOfWarning() {
        Instant now = Instant.now();
        Instant inTwoDays = now.plus(Duration.ofDays(2));
        Instant cutoff = now.plus(Duration.ofDays(3));
        Instant monthAgo = now.minus(Duration.ofDays(30));

        User trial = user(Plan.PRO, inTwoDays, now.minus(Duration.ofDays(12)), false, null);
        User paidNoRenew = user(Plan.PRO, inTwoDays, null, false, null);
        paid(paidNoRenew);
        User renewing = user(Plan.PRO, inTwoDays, null, true, "card-token");
        User trialThenPaid = user(Plan.PRO, inTwoDays, monthAgo, false, null);
        paid(trialThenPaid);
        User trialThenGranted = user(Plan.FREE, null, monthAgo, false, null);
        adminPlans.change(trialThenGranted.getId(), Plan.PRO, 1, null, "admin@majstr.test");
        jdbc.update("UPDATE users SET plan_expires_at = ? WHERE id = ?", Timestamp.from(inTwoDays),
                trialThenGranted.getId());
        User farOff = user(Plan.PRO, now.plus(Duration.ofDays(20)), null, false, null);

        var ending = userRepository.findPlanEndingCandidates(now, cutoff).stream().map(User::getId).toList();
        var trialEnding = userRepository.findTrialEndingReminderDue(now, cutoff, now.minus(Duration.ofDays(1)))
                .stream().map(User::getId).toList();

        assertThat(ending).contains(paidNoRenew.getId(), trialThenPaid.getId(), trialThenGranted.getId())
                .doesNotContain(trial.getId(), renewing.getId(), farOff.getId());
        assertThat(trialEnding).contains(trial.getId())
                .doesNotContain(trialThenPaid.getId(), trialThenGranted.getId(), paidNoRenew.getId());
    }
}
