package com.majstr.backend.integration;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the nightly downgrade actually picks up — against a real database, because the rule lives in a
 * JPQL query and the unit test for the service mocks the repository away.
 *
 * <p><b>Why this exists.</b> A master's 5-day trial was still PRO on the ninth day. Nothing was
 * broken: the query folded a 3-day grace window into one cutoff for everyone, and grace is meant for
 * a late CHARGE — five days of trial plus three of waiting for a charge that could never arrive
 * (no card, auto-renew off) plus the wait for the next 03:30 run. The unit test could not see it,
 * because the boundary it got wrong was inside the query string.</p>
 */
class BillingExpiryQueryIntegrationTest extends IntegrationTestBase {

    private static final int GRACE_DAYS = 3;

    @Autowired UserRepository userRepository;

    @Test
    void aTrialExpiresOnTime_whileARenewableSubscriptionKeepsItsGrace() {
        Instant now = Instant.now();
        Instant graceCutoff = now.minus(GRACE_DAYS, ChronoUnit.DAYS);

        // The production case: trial ended an hour ago, no card, auto-renew off.
        User trial = save(user("trial", Plan.PRO, now.minus(Duration.ofHours(1)), false, null, now.minus(Duration.ofDays(5))));
        // A paid subscription with a charge pending — grace is exactly for this one.
        User renewableJustLapsed = save(user("renew-fresh", Plan.PRO, now.minus(Duration.ofHours(1)), true, "card-tok", null));
        // Same, but grace is spent.
        User renewableLongLapsed = save(user("renew-stale", Plan.PRO, now.minus(Duration.ofDays(5)), true, "card-tok", null));
        // Still inside its term — must never be touched.
        User live = save(user("live", Plan.PRO, now.plus(Duration.ofDays(10)), true, "card-tok", null));
        // Admin-granted PRO with no expiry at all.
        User granted = save(user("granted", Plan.PRO, null, false, null, null));

        List<UUID> picked = userRepository.findExpiredSubscriptions(now, graceCutoff)
                .stream().map(User::getId).toList();

        assertThat(picked)
                .as("тріал закінчився — grace йому не належить, бо списувати нічого")
                .contains(trial.getId())
                .as("платна підписка з карткою чекає на списання — grace діє")
                .doesNotContain(renewableJustLapsed.getId())
                .as("grace вичерпано")
                .contains(renewableLongLapsed.getId())
                .as("термін ще не вийшов")
                .doesNotContain(live.getId())
                .as("видана адміном підписка без строку не чіпається")
                .doesNotContain(granted.getId());
    }

    @Test
    void aPaidSubscriberWhoTurnedAutoRenewOffAlsoExpiresOnTime() {
        // A deliberate behaviour change worth stating: with auto-renew off there is no pending charge,
        // so there is nothing for grace to protect. They lose PRO the first night after expiry rather
        // than three days later. Called out because it is not only the trial that this affects.
        Instant now = Instant.now();
        User cancelled = save(user("cancelled", Plan.PRO, now.minus(Duration.ofHours(2)), false, "card-tok", null));

        assertThat(userRepository.findExpiredSubscriptions(now, now.minus(GRACE_DAYS, ChronoUnit.DAYS)))
                .extracting(User::getId)
                .contains(cancelled.getId());
    }

    private User save(User user) {
        return userRepository.save(user);
    }

    private User user(String tag, Plan plan, Instant expiresAt,
                      boolean autoRenew, String cardToken, Instant trialStartedAt) {
        String email = tag + "-" + UUID.randomUUID() + "@expiry.test";
        return User.builder()
                .email(email)
                .emailCanonical(email)
                .passwordHash("x")
                .fullName("Тест " + tag)
                .phone("+380500000000")
                .companyName("ФОП")
                .referralCode(UUID.randomUUID().toString().substring(0, 12))
                .plan(plan)
                .planExpiresAt(expiresAt)
                .autoRenew(autoRenew)
                .cardToken(cardToken)
                .trialStartedAt(trialStartedAt)
                .build();
    }
}
