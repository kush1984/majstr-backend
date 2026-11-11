package com.majstr.backend.integration;

import com.majstr.backend.dto.AdminUserActivity;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.User;
import com.majstr.backend.repository.UserActivitySessionRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ActivitySessionService;
import com.majstr.backend.service.AdminActivityService;
import com.majstr.backend.service.AdminPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V199 against Postgres: a session is opened and stretched by the tracker's touches, closed by
 * 30 minutes of silence, summed per window, and both new tables go with the account.
 */
@SpringBootTest
class ActivitySessionsIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired UserActivitySessionRepository sessionRepository;
    @Autowired ActivitySessionService sessions;
    @Autowired AdminActivityService adminActivity;
    @Autowired AdminPlanService adminPlans;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    private User user;
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(txManager);
        String unique = UUID.randomUUID().toString();
        String email = unique + "@majstr.test";
        user = userRepository.save(User.builder()
                .email(email).emailCanonical(email).passwordHash("x").fullName("Сеанси")
                .plan(Plan.FREE).role(Role.USER).referralCode(unique.substring(0, 10)).build());
    }

    private void touch(Instant at) {
        tx.executeWithoutResult(s -> sessions.record(user.getId(), at));
    }

    @Test
    void touchesWithinThirtyMinutesStretchOneSession_aLongerSilenceOpensTheNext() {
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        touch(start);
        touch(start.plus(Duration.ofMinutes(10)));
        touch(start.plus(Duration.ofMinutes(25)));      // 15 min after the last one — same session
        touch(start.plus(Duration.ofMinutes(70)));      // 45 min of silence — a new one

        AdminUserActivity a = adminActivity.activity(user.getId());

        assertThat(a.sessions()).hasSize(2);
        AdminUserActivity.Session older = a.sessions().get(1);
        assertThat(older.startedAt()).isEqualTo(start);
        assertThat(older.endedAt()).isEqualTo(start.plus(Duration.ofMinutes(25)));
        assertThat(older.minutes()).isEqualTo(25);
        assertThat(older.open()).isFalse();
        assertThat(a.minutesLast7Days()).isEqualTo(25);
        assertThat(a.minutesLast30Days()).isEqualTo(25);
    }

    @Test
    void aSessionThatBeganBeforeTheWindowCountsOnlyItsPartInside() {
        Instant eightDaysAgo = Instant.now().minus(Duration.ofDays(7)).minus(Duration.ofMinutes(20));
        for (int m = 0; m <= 40; m += 5) {
            touch(eightDaysAgo.plus(Duration.ofMinutes(m)));
        }

        AdminUserActivity a = adminActivity.activity(user.getId());

        assertThat(a.sessions()).hasSize(1);
        assertThat(a.minutesLast30Days()).isEqualTo(40);
        assertThat(a.minutesLast7Days()).isBetween(19L, 21L); // the 20 minutes past the 7-day line
    }

    @Test
    void aRecentTouchIsReportedAsStillOpen() {
        touch(Instant.now().minus(Duration.ofMinutes(3)));

        assertThat(adminActivity.activity(user.getId()).sessions().get(0).open()).isTrue();
    }

    @Test
    void aGrantIsRecordedAndBothTablesGoWithTheAccount() {
        touch(Instant.now());
        adminPlans.change(user.getId(), Plan.PRO, 2, null, "admin@majstr.test");

        AdminUserActivity a = adminActivity.activity(user.getId());
        assertThat(a.planGrants()).singleElement().satisfies(g -> {
            assertThat(g.by()).isEqualTo("admin@majstr.test");
            assertThat(g.months()).isEqualTo(2);
            assertThat(g.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(58)));
        });
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPlanExpiresAt())
                .isEqualTo(a.planGrants().get(0).expiresAt());

        tx.executeWithoutResult(s -> userRepository.deleteCascading(user.getId()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_activity_session WHERE user_id = ?",
                Long.class, user.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_plan_grant WHERE user_id = ?",
                Long.class, user.getId())).isZero();
    }

    @Test
    void retentionDropsSessionsOlderThanNinetyDays() {
        touch(Instant.now().minus(Duration.ofDays(91)));
        touch(Instant.now().minus(Duration.ofDays(1)));

        sessions.purgeOld();

        assertThat(sessionRepository.findAll().stream().filter(s -> s.getUserId().equals(user.getId())))
                .hasSize(1);
    }
}
